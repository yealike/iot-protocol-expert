package top.yealike.ipe.ops.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.ipe.ops.config.OpsProperties;
import top.yealike.ipe.ops.device.entity.DeviceChannel;
import top.yealike.ipe.ops.device.entity.DeviceInstance;
import top.yealike.ipe.ops.device.entity.DeviceTag;
import top.yealike.ipe.ops.device.entity.Product;
import top.yealike.ipe.ops.device.repository.DeviceChannelRepository;
import top.yealike.ipe.ops.device.repository.DeviceInstanceRepository;
import top.yealike.ipe.ops.device.repository.DeviceTagRepository;
import top.yealike.ipe.ops.device.repository.ProductRepository;
import top.yealike.ipe.ops.device.service.DeviceInstanceService;
import top.yealike.ipe.ops.device.service.DevicePropertyService;
import top.yealike.ipe.ops.dto.OpsDtos;

/**
 * 协议侧设备服务：登录、心跳、离线与协议专属字段的落位。
 *
 * <p>和 {@link DeviceInstanceService} 的分工：这里是「协议通道看到的设备」， 只做定位、准入与运行状态维护；档案的增删改查在设备实例服务里。
 *
 * <p>协议专属字段（桩类型、SIM 卡号、厂商型号、连接器数）写进 {@link DeviceTag}， 不再污染主表——这是本次模型改造最直接的一条收益。
 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceInstanceService instances;
    private final DeviceInstanceRepository devices;
    private final DeviceTagRepository tags;
    private final DeviceChannelRepository channels;
    private final ProductRepository products;
    private final DevicePropertyService properties;
    private final StationService stations;
    private final OpsProperties options;

    public DeviceService(
            DeviceInstanceService instances,
            DeviceInstanceRepository devices,
            DeviceTagRepository tags,
            DeviceChannelRepository channels,
            ProductRepository products,
            DevicePropertyService properties,
            StationService stations,
            OpsProperties options) {
        this.instances = instances;
        this.devices = devices;
        this.tags = tags;
        this.channels = channels;
        this.products = products;
        this.properties = properties;
        this.stations = stations;
        this.options = options;
    }

    public DeviceInstance get(Long id) {
        return instances.get(id);
    }

    public DeviceInstance resolve(String protocol, String protocolDeviceId) {
        return instances.resolve(protocol, protocolDeviceId);
    }

    /** 按平台编码模糊匹配（排障入口用）。 */
    public List<DeviceInstance> searchByCode(String code) {
        String key = code == null ? "" : code.trim().toLowerCase();
        if (key.isEmpty()) {
            return List.of();
        }
        return devices.findAll().stream()
                .filter(device -> device.deviceCode != null
                        && device.deviceCode.toLowerCase().contains(key))
                .toList();
    }

    public List<DeviceInstance> simulatorDevices() {
        return instances.simulatorDevices();
    }

    /** 协议通道登录事件：以协议编码 + 协议侧编号定位档案，返回平台设备。 */
    @Transactional
    public DeviceInstance login(OpsDtos.LoginRequest request) {
        String protocol = DeviceInstanceService.normalizeProtocol(request.protocol());
        String code =
                request.protocolDeviceId() == null || request.protocolDeviceId().isBlank()
                        ? request.pileCode()
                        : request.protocolDeviceId();
        if (code == null || code.isBlank()) {
            throw new DeviceRejectedException("协议侧设备编号为空");
        }
        DeviceInstance device =
                devices.findByProtocolAndProtocolDeviceId(protocol, code).orElseGet(() -> autoRegister(protocol, code));

        if (!device.enabled) {
            throw new DeviceRejectedException("设备已停用: " + device.deviceCode);
        }
        if (!device.protocol.equals(protocol)) {
            device.protocol = protocol;
        }
        if (request.protocolDeviceId() != null && !request.protocolDeviceId().isBlank()) {
            device.protocolDeviceId = code;
        }
        applyProtocolTags(device, request);
        DeviceInstance saved = instances.markOnline(device, request.sessionId());
        properties.write(saved.id, "", "state", "idle");
        ensureChannelCount(saved, request.gunCount() > 0 ? request.gunCount() : 1);
        return saved;
    }

    /** OCPP BootNotification 建档或更新运行信息。 */
    @Transactional
    public DeviceInstance bootOcpp(OpsDtos.LoginRequest request) {
        DeviceInstance device = login(request);
        Map<String, String> extra = new LinkedHashMap<>();
        put(extra, "chargePointVendor", request.vendor());
        put(extra, "chargePointModel", request.model());
        put(extra, "chargePointSerialNumber", request.serialNumber());
        put(extra, "programVersion", request.firmwareVersion());
        put(extra, "connectorCount", String.valueOf(Math.max(request.connectorCount(), 1)));
        writeTags(device.id, extra);
        ensureChannelCount(device, Math.max(request.connectorCount(), 1));
        return devices.findById(device.id).orElse(device);
    }

    private DeviceInstance autoRegister(String protocol, String code) {
        if (!options.isAutoRegister()) {
            throw new DeviceRejectedException("未登记设备: " + protocol + "/" + code);
        }
        Product product = products.findByProtocolAndState(protocol, "PUBLISHED").stream()
                .findFirst()
                .or(() -> products.findAll().stream()
                        .filter(item -> protocol.equals(item.protocol))
                        .findFirst())
                .orElseThrow(() -> new DeviceRejectedException("没有用于 " + protocol + " 的产品模板，无法自动登记设备 " + code));
        DeviceInstance device = new DeviceInstance();
        device.deviceCode = "AUTO-" + protocol + "-" + code;
        device.name = "自动登记 " + protocol + " " + code;
        device.productId = product.id;
        device.deviceType = product.deviceType;
        device.protocol = protocol;
        device.protocolDeviceId = code;
        device.stationId = stations.ensureDefault(options.getDefaultStationCode(), options.getDefaultStationName()).id;
        device.state = DeviceInstance.STATE_OFFLINE;
        device.registryTime = LocalDateTime.now();
        device.createdAt = LocalDateTime.now();
        device.updatedAt = LocalDateTime.now();
        log.info("自动登记设备 {} / {}", protocol, code);
        return devices.save(device);
    }

    private void applyProtocolTags(DeviceInstance device, OpsDtos.LoginRequest request) {
        Map<String, String> values = new LinkedHashMap<>();
        if (request.pileType() > 0) {
            values.put("pileType", String.valueOf(request.pileType()));
        }
        if (request.protocolVersion() > 0) {
            values.put("protocolVersion", String.valueOf(request.protocolVersion()));
        }
        put(values, "programVersion", request.programVersion());
        values.put("networkType", String.valueOf(request.networkType()));
        put(values, "simCard", request.simCard());
        values.put("operator", String.valueOf(request.operator()));
        put(values, "chargePointVendor", request.vendor());
        put(values, "chargePointModel", request.model());
        put(values, "chargePointSerialNumber", request.serialNumber());
        if (request.gunCount() > 0) {
            values.put("gunCount", String.valueOf(request.gunCount()));
        } else if (request.connectorCount() > 0) {
            values.put("gunCount", String.valueOf(request.connectorCount()));
        }
        writeTags(device.id, values);
    }

    private void writeTags(Long deviceId, Map<String, String> values) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            DeviceTag tag = tags.findByDeviceIdAndTagKey(deviceId, entry.getKey())
                    .orElseGet(() -> new DeviceTag(deviceId, entry.getKey(), null, "string"));
            tag.tagValue = entry.getValue();
            tag.updatedAt = LocalDateTime.now();
            tags.save(tag);
        }
    }

    private static void put(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private void ensureChannelCount(DeviceInstance device, int count) {
        int target = Math.max(count, 1);
        for (int i = 1; i <= target; i++) {
            String channelNo = String.format("%02d", i);
            if (channels.findByDeviceIdAndChannelNo(device.id, channelNo).isPresent()) {
                continue;
            }
            DeviceChannel channel = new DeviceChannel();
            channel.deviceId = device.id;
            channel.channelNo = channelNo;
            channel.channelType = "CONNECTOR";
            channel.name = "充电枪 " + channelNo;
            channel.createdAt = LocalDateTime.now();
            channel.updatedAt = LocalDateTime.now();
            channels.save(channel);
        }
    }

    public void touch(String protocol, String protocolDeviceId) {
        instances.touch(protocol, protocolDeviceId);
    }

    public void markOffline(String protocol, String protocolDeviceId, String sessionId) {
        instances.markOffline(protocol, protocolDeviceId, sessionId);
    }

    @Transactional
    public DeviceInstance patch(String protocol, String protocolDeviceId, OpsDtos.DevicePatch patch) {
        DeviceInstance device = resolve(protocol, protocolDeviceId);
        return instances.setAllowWork(device.id, patch.allowWork(), patch.maxPowerPercent());
    }

    /** 充电枪数：只数 CONNECTOR 通道。 */
    public int gunCount(Long deviceId) {
        return (int) channels.countByDeviceIdAndChannelType(deviceId, "CONNECTOR");
    }

    /** 内部契约视图：字段名与协议通道的 {@code OpsDtos.DeviceView} 保持一致。 */
    public OpsDtos.DeviceView view(DeviceInstance device) {
        Map<String, String> tagValues = new LinkedHashMap<>();
        for (DeviceTag tag : tags.findByDeviceIdOrderByTagKeyAsc(device.id)) {
            tagValues.put(tag.tagKey, tag.tagValue);
        }
        String stationName = device.stationId == null ? null : stations.get(device.stationId).name;
        return new OpsDtos.DeviceView(
                device.id,
                device.deviceCode,
                device.protocol,
                device.protocolDeviceId,
                device.stationId,
                stationName,
                gunCount(device.id),
                device.enabled,
                device.allowSimulate,
                device.allowWork,
                properties
                        .decimalOf(device.id, "", "maxPowerPercent", java.math.BigDecimal.valueOf(100))
                        .intValue(),
                device.isOnline(),
                device.lastSeen,
                intTag(tagValues, "protocolVersion", 0),
                tagValues.get("programVersion"),
                intTag(tagValues, "pileType", 0),
                intTag(tagValues, "networkType", 0),
                tagValues.get("simCard"),
                intTag(tagValues, "operator", 0),
                tagValues.get("chargePointVendor"),
                tagValues.get("chargePointModel"));
    }

    private static int intTag(Map<String, String> tags, String key, int fallback) {
        String raw = tags.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static String normalizeProtocol(String protocol) {
        return DeviceInstanceService.normalizeProtocol(protocol);
    }

    public Optional<DeviceInstance> find(Long id) {
        return devices.findById(id);
    }

    public static class DeviceRejectedException extends RuntimeException {
        public DeviceRejectedException(String message) {
            super(message);
        }
    }
}
