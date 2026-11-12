package top.yealike.ipe.ops.device.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.ipe.ops.alarm.service.AlarmService;
import top.yealike.ipe.ops.device.dto.DeviceDtos;
import top.yealike.ipe.ops.device.entity.DeviceChannel;
import top.yealike.ipe.ops.device.entity.DeviceInstance;
import top.yealike.ipe.ops.device.entity.DevicePropertyLatest;
import top.yealike.ipe.ops.device.entity.DevicePropertyTs;
import top.yealike.ipe.ops.device.repository.DeviceChannelRepository;
import top.yealike.ipe.ops.device.repository.DeviceInstanceRepository;
import top.yealike.ipe.ops.device.repository.DevicePropertyLatestRepository;
import top.yealike.ipe.ops.device.repository.DevicePropertyTsRepository;
import top.yealike.ipe.ops.thingmodel.DeviceTypeCatalog;
import top.yealike.ipe.ops.thingmodel.ThingModel;
import top.yealike.ipe.ops.thingmodel.ThingValues;

/**
 * 属性服务：把协议通道送来的原始值翻译成「属性最新值 + 时序」。
 *
 * <p>这是改造前后差别最大的地方。原来每种设备各有一张实时值表（枪用 {@code gun_state}）， 新设备类型到来时无处可放。现在统一走属性模型：
 *
 * <pre>
 *   值 -> 物模型类型 -> 范围校验 -> 选列（num/text/json/time）-> upsert latest -> append ts
 * </pre>
 *
 * 校验发生在入库前，非法值会被拒绝并返回原因，而不是污染数据。
 */
@Service
public class DevicePropertyService {

    private static final Logger log = LoggerFactory.getLogger(DevicePropertyService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DevicePropertyLatestRepository latest;
    private final DevicePropertyTsRepository series;
    private final DeviceInstanceRepository devices;
    private final DeviceChannelRepository channels;
    private final ThingModelResolver models;
    private final AlarmService alarms;

    public DevicePropertyService(
            DevicePropertyLatestRepository latest,
            DevicePropertyTsRepository series,
            DeviceInstanceRepository devices,
            DeviceChannelRepository channels,
            ThingModelResolver models,
            AlarmService alarms) {
        this.latest = latest;
        this.series = series;
        this.devices = devices;
        this.channels = channels;
        this.models = models;
        this.alarms = alarms;
    }

    // ------------------------------------------------------------------ 写入

    /** 单属性写入。 */
    @Transactional
    public DevicePropertyLatest write(Long deviceId, String channelNo, String propertyId, Object raw) {
        return write(deviceId, channelNo, propertyId, raw, LocalDateTime.now());
    }

    @Transactional
    public DevicePropertyLatest write(
            Long deviceId, String channelNo, String propertyId, Object raw, LocalDateTime occurredAt) {
        DeviceInstance device =
                devices.findById(deviceId).orElseThrow(() -> new IllegalArgumentException("设备不存在: " + deviceId));
        ThingModel model = models.of(device);
        return writeInternal(device, model, channelNo, propertyId, raw, occurredAt);
    }

    /**
     * 批量写入同一通道的值。只查一次设备与规则，避免逐条 round-trip。
     *
     * @return 实际写入的属性条数
     */
    @Transactional
    public int writeBatch(Long deviceId, String channelNo, Map<String, Object> values, LocalDateTime occurredAt) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        DeviceInstance device =
                devices.findById(deviceId).orElseThrow(() -> new IllegalArgumentException("设备不存在: " + deviceId));
        return writeBatchInternal(device, channelNo, values, occurredAt == null ? LocalDateTime.now() : occurredAt);
    }

    @Transactional
    public int writeBatchByProtocol(
            String protocol,
            String protocolDeviceId,
            String channelNo,
            Map<String, Object> values,
            LocalDateTime occurredAt) {
        DeviceInstance device = devices.findByProtocolAndProtocolDeviceId(protocol, protocolDeviceId)
                .orElseThrow(() -> new IllegalArgumentException("设备未登记: " + protocol + "/" + protocolDeviceId));
        return writeBatchInternal(device, channelNo, values, occurredAt == null ? LocalDateTime.now() : occurredAt);
    }

    /** Generic gateway telemetry must use a registered, enabled device and declared properties. */
    @Transactional
    public int writeGatewayBatch(
            String protocol,
            String protocolDeviceId,
            String channelNo,
            Map<String, Object> values,
            LocalDateTime occurredAt) {
        DeviceInstance device = devices.findByProtocolAndProtocolDeviceId(protocol, protocolDeviceId)
                .orElseThrow(() -> new IllegalArgumentException("设备未登记: " + protocol + "/" + protocolDeviceId));
        if (!device.enabled || (device.protocolDeviceId.startsWith("SIM-") && !device.allowSimulate)) {
            throw new IllegalArgumentException("设备未启用或不允许模拟: " + device.deviceCode);
        }
        if (values == null || values.isEmpty() || values.size() > 100) {
            throw new IllegalArgumentException("属性数量必须为 1 至 100");
        }
        ThingModel model = models.of(device);
        for (String key : values.keySet()) {
            var property = model.property(key).orElseThrow(() -> new IllegalArgumentException("物模型未定义属性: " + key));
            if (values.get(key) == null) {
                throw new IllegalArgumentException("属性值不能为空: " + key);
            }
            if (property.channel() && (channelNo == null || channelNo.isBlank())) {
                throw new IllegalArgumentException("通道属性必须提供通道号: " + key);
            }
        }
        int written =
                writeBatchInternal(device, channelNo, values, occurredAt == null ? LocalDateTime.now() : occurredAt);
        if (written > 0 && !"CHARGER".equals(device.deviceType)) {
            LocalDateTime now = LocalDateTime.now();
            if (!device.isOnline()) {
                device.state = DeviceInstance.STATE_ONLINE;
                device.onlineAt = now;
            }
            device.lastSeen = now;
            device.updatedAt = now;
            device.sessionId = "gateway-telemetry";
            devices.save(device);
        }
        return written;
    }

    private int writeBatchInternal(
            DeviceInstance device, String channelNo, Map<String, Object> values, LocalDateTime occurredAt) {
        ThingModel model = models.of(device);
        int written = 0;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            try {
                writeInternal(device, model, channelNo, entry.getKey(), entry.getValue(), occurredAt);
                written++;
            } catch (IllegalArgumentException e) {
                // 单个属性非法不应中断整帧：记录后继续，避免一个坏字段丢掉整批遥测
                log.warn("设备 {} 属性 {} 写入被拒绝: {}", device.deviceCode, entry.getKey(), e.getMessage());
            }
        }
        return written;
    }

    private DevicePropertyLatest writeInternal(
            DeviceInstance device,
            ThingModel model,
            String channelNo,
            String propertyId,
            Object raw,
            LocalDateTime occurredAt) {
        String key = propertyId == null ? "" : propertyId.trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("属性 id 不能为空");
        }
        ThingModel.PropertyMetadata metadata = model.property(key).orElse(null);
        var type = metadata == null ? null : metadata.valueType();
        boolean channelScoped = channelNo != null && !channelNo.isBlank() && metadata != null && metadata.channel();
        String effectiveChannel = channelScoped ? channelNo : "";

        ThingValues.StoredValue value = ThingValues.coerce(type, raw);

        if (channelScoped) {
            ensureChannel(device, channelNo, metadata);
        }

        DevicePropertyLatest row = latest.findByDeviceIdAndChannelNoAndPropertyId(device.id, effectiveChannel, key)
                .orElseGet(() -> new DevicePropertyLatest(device.id, effectiveChannel, key, value.valueType()));
        row.valueType = value.valueType();
        row.valueNum = value.num();
        row.valueText = value.text();
        row.valueJson = value.json();
        row.valueTime = value.time();
        row.updatedAt = LocalDateTime.now();
        latest.save(row);

        appendSeries(device.id, effectiveChannel, key, value, occurredAt);

        if (value.num() != null) {
            alarms.evaluate(device, effectiveChannel, key, value.num(), occurredAt);
        }
        return row;
    }

    private void ensureChannel(DeviceInstance device, String channelNo, ThingModel.PropertyMetadata metadata) {
        if (channels.findByDeviceIdAndChannelNo(device.id, channelNo).isPresent()) {
            return;
        }
        DeviceChannel channel = new DeviceChannel();
        channel.deviceId = device.id;
        channel.channelNo = channelNo;
        channel.channelType = DeviceTypeCatalog.find(device.deviceType)
                .map(DeviceTypeCatalog::channelType)
                .orElse("CHANNEL");
        channel.name = metadata == null ? channelNo : channelNo + " (自动创建)";
        channel.createdAt = LocalDateTime.now();
        channel.updatedAt = LocalDateTime.now();
        channels.save(channel);
    }

    private void appendSeries(
            Long deviceId,
            String channelNo,
            String propertyId,
            ThingValues.StoredValue value,
            LocalDateTime collectedAt) {
        if (value.isNull()) {
            return;
        }
        DevicePropertyTs point = new DevicePropertyTs(
                deviceId, channelNo, propertyId, value.num(), value.text(), value.json(), collectedAt);
        series.save(point);
    }

    // ------------------------------------------------------------------ 读取

    public Map<String, DevicePropertyLatest> latestMap(Long deviceId) {
        Map<String, DevicePropertyLatest> map = new LinkedHashMap<>();
        for (DevicePropertyLatest row : latest.findByDeviceId(deviceId)) {
            map.put(row.channelNo + "|" + row.propertyId, row);
        }
        return map;
    }

    public Optional<DevicePropertyLatest> find(Long deviceId, String channelNo, String propertyId) {
        return latest.findByDeviceIdAndChannelNoAndPropertyId(deviceId, channelNo == null ? "" : channelNo, propertyId);
    }

    public BigDecimal decimalOf(Long deviceId, String channelNo, String propertyId, BigDecimal fallback) {
        return find(deviceId, channelNo, propertyId).map(row -> row.valueNum).orElse(fallback);
    }

    public String textOf(Long deviceId, String channelNo, String propertyId, String fallback) {
        return find(deviceId, channelNo, propertyId).map(row -> row.valueText).orElse(fallback);
    }

    public boolean boolOf(Long deviceId, String channelNo, String propertyId, boolean fallback) {
        return find(deviceId, channelNo, propertyId)
                .map(row -> row.valueNum == null ? fallback : row.valueNum.signum() != 0)
                .orElse(fallback);
    }

    /** 平铺的属性视图，供详情页与 3D 标签使用。 */
    public List<DeviceDtos.PropertyValue> propertyViews(DeviceInstance device, ThingModel model) {
        Map<String, DevicePropertyLatest> stored = latestMap(device.id);
        List<DeviceDtos.PropertyValue> result = new ArrayList<>();
        for (ThingModel.PropertyMetadata property : model.properties()) {
            if (property.channel()) {
                continue;
            }
            result.add(toView(property, stored.get("|" + property.id())));
        }
        // 物模型里没声明的历史属性也要能看见，否则数据「丢在库里」
        Set<String> declared = new LinkedHashSet<>();
        model.properties().forEach(p -> declared.add(p.id()));
        for (DevicePropertyLatest row : stored.values()) {
            if (!row.channelNo.isEmpty() || declared.contains(row.propertyId)) {
                continue;
            }
            result.add(toView(null, row));
        }
        return result;
    }

    /** 按通道分组的属性视图。通道来自 device_channel，属性来自物模型 + 已存值。 */
    public List<DeviceDtos.ChannelView> channelViews(DeviceInstance device, ThingModel model) {
        Map<String, DevicePropertyLatest> stored = latestMap(device.id);
        List<DeviceDtos.ChannelView> result = new ArrayList<>();
        List<ThingModel.PropertyMetadata> channelProperties = model.channelProperties();

        for (DeviceChannel channel : channels.findByDeviceIdOrderByChannelNoAsc(device.id)) {
            List<DeviceDtos.PropertyValue> values = new ArrayList<>();
            for (ThingModel.PropertyMetadata property : channelProperties) {
                values.add(toView(property, stored.get(channel.channelNo + "|" + property.id())));
            }
            result.add(new DeviceDtos.ChannelView(
                    channel.channelNo,
                    channel.channelType,
                    channel.name == null ? channel.channelNo : channel.name,
                    channel.enabled,
                    values));
        }

        // 有值但没有通道登记的情况（协议直接上报了未登记的枪号）
        Set<String> registered = new LinkedHashSet<>();
        result.forEach(view -> registered.add(view.channelNo()));
        for (DevicePropertyLatest row : stored.values()) {
            if (row.channelNo.isEmpty() || registered.contains(row.channelNo)) {
                continue;
            }
            List<DeviceDtos.PropertyValue> values = new ArrayList<>();
            for (ThingModel.PropertyMetadata property : channelProperties) {
                values.add(toView(property, stored.get(row.channelNo + "|" + property.id())));
            }
            result.add(new DeviceDtos.ChannelView(row.channelNo, "CHANNEL", row.channelNo, true, values));
            registered.add(row.channelNo);
        }
        return result;
    }

    private DeviceDtos.PropertyValue toView(ThingModel.PropertyMetadata metadata, DevicePropertyLatest row) {
        String propertyId = metadata != null ? metadata.id() : (row == null ? "" : row.propertyId);
        String name = metadata != null && metadata.name() != null ? metadata.name() : propertyId;
        String valueType = metadata != null && metadata.valueType() != null
                ? metadata.valueType().typeId()
                : (row == null ? "unknown" : row.valueType);
        String unit = metadata != null && metadata.valueType() != null
                ? metadata.valueType().unit()
                : null;
        boolean writable = metadata != null && !metadata.readOnly();
        boolean channelScoped = metadata != null && metadata.channel();
        String channelNo = row == null ? "" : row.channelNo;

        ThingValues.StoredValue value = row == null
                ? null
                : new ThingValues.StoredValue(row.valueType, row.valueNum, row.valueText, row.valueJson, row.valueTime);
        Object raw = null;
        if (row != null) {
            raw = row.valueNum != null ? row.valueNum : row.valueText != null ? row.valueText : row.valueJson;
        }
        String display = ThingValues.format(metadata == null ? null : metadata.valueType(), value);
        String updatedAt = row == null || row.updatedAt == null ? null : row.updatedAt.format(TIME);

        return new DeviceDtos.PropertyValue(
                propertyId, name, channelNo, valueType, raw, display, unit, updatedAt, writable, channelScoped);
    }

    public DeviceDtos.SeriesView series(
            Long deviceId, String channelNo, String propertyId, LocalDateTime from, LocalDateTime to) {
        DeviceInstance device =
                devices.findById(deviceId).orElseThrow(() -> new IllegalArgumentException("设备不存在: " + deviceId));
        ThingModel model = models.of(device);
        ThingModel.PropertyMetadata metadata = model.property(propertyId).orElse(null);
        LocalDateTime start = from == null ? LocalDateTime.now().minusHours(24) : from;
        LocalDateTime end = to == null ? LocalDateTime.now() : to;

        List<DeviceDtos.SeriesPoint> points = new ArrayList<>();
        for (DevicePropertyTs row : series.findByDeviceIdAndPropertyIdAndCollectedAtBetweenOrderByCollectedAtAsc(
                deviceId, propertyId, start, end)) {
            if (channelNo != null && !channelNo.isBlank() && !channelNo.equals(row.channelNo)) {
                continue;
            }
            points.add(new DeviceDtos.SeriesPoint(
                    row.collectedAt.format(TIME),
                    row.valueNum == null ? null : row.valueNum.doubleValue(),
                    row.valueText));
        }
        return new DeviceDtos.SeriesView(
                deviceId,
                propertyId,
                channelNo,
                metadata == null ? propertyId : metadata.name(),
                metadata == null || metadata.valueType() == null
                        ? null
                        : metadata.valueType().unit(),
                points);
    }
}
