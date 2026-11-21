package top.yealike.pep.ops.devices.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.pep.ops.api.dto.OpsDtos;
import top.yealike.pep.ops.devices.entity.DeviceInstance;
import top.yealike.pep.ops.devices.entity.DeviceTag;
import top.yealike.pep.ops.devices.mapper.DeviceChannelMapper;
import top.yealike.pep.ops.devices.mapper.DeviceInstanceMapper;
import top.yealike.pep.ops.devices.mapper.DeviceTagMapper;

/** 协议通道看到的充电桩：只做准入、运行状态与协议观测信息维护。 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceInstanceService instances;
    private final DeviceInstanceMapper devices;
    private final DeviceTagMapper tags;
    private final DeviceChannelMapper channels;
    private final DevicePropertyService properties;

    public DeviceService(
            DeviceInstanceService instances,
            DeviceInstanceMapper devices,
            DeviceTagMapper tags,
            DeviceChannelMapper channels,
            DevicePropertyService properties) {
        this.instances = instances;
        this.devices = devices;
        this.tags = tags;
        this.channels = channels;
        this.properties = properties;
    }

    public DeviceInstance get(Long id) {
        return instances.get(id);
    }

    public DeviceInstance resolve(String protocol, String identifier) {
        try {
            return instances.resolve(protocol, identifier);
        } catch (DeviceInstanceService.DeviceRejectedException e) {
            throw new DeviceRejectedException(e.getMessage());
        }
    }

    public List<DeviceInstance> searchByCode(String code) {
        String keyword = code == null ? "" : code.trim().toLowerCase(java.util.Locale.ROOT);
        if (keyword.isEmpty()) {
            return List.of();
        }
        return devices.findAll().stream()
                .filter(device -> device.deviceCode != null
                        && device.deviceCode.toLowerCase(java.util.Locale.ROOT).contains(keyword))
                .toList();
    }

    public List<DeviceInstance> simulatorDevices() {
        return instances.simulatorDevices();
    }

    /** 协议登录：已有映射或平台 SN 才能进入，绝不自动建档。 */
    @Transactional
    public DeviceInstance login(OpsDtos.LoginRequest request) {
        String protocol = DeviceInstanceService.normalizeProtocol(request.protocol());
        String identifier =
                request.protocolDeviceId() == null || request.protocolDeviceId().isBlank()
                        ? request.pileCode()
                        : request.protocolDeviceId();
        DeviceInstance device = resolve(protocol, identifier);
        if (!device.enabled) {
            throw new DeviceRejectedException("设备已停用: " + device.deviceCode);
        }
        int expectedGuns = gunCount(device.id);
        int reportedGuns = request.gunCount() > 0 ? request.gunCount() : request.connectorCount();
        if (reportedGuns > 0 && reportedGuns != expectedGuns) {
            throw new DeviceRejectedException("设备枪数与产品标准枪数不一致: 上报 " + reportedGuns + "，平台 " + expectedGuns);
        }
        applyProtocolTags(device, request);
        DeviceInstance saved = instances.markOnline(device, request.sessionId());
        properties.write(saved.id, "", "state", "idle");
        log.info("充电桩 {} 登录，协议 {}", saved.deviceCode, saved.protocol);
        return saved;
    }

    @Transactional
    public DeviceInstance bootOcpp(OpsDtos.LoginRequest request) {
        DeviceInstance device = login(request);
        Map<String, String> extra = new LinkedHashMap<>();
        put(extra, "chargePointVendor", request.vendor());
        put(extra, "chargePointModel", request.model());
        put(extra, "chargePointSerialNumber", request.serialNumber());
        put(extra, "programVersion", request.firmwareVersion());
        writeTags(device.id, extra);
        return devices.findById(device.id).orElse(device);
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
        put(values, "simCard", request.simCard());
        put(values, "chargePointVendor", request.vendor());
        put(values, "chargePointModel", request.model());
        put(values, "chargePointSerialNumber", request.serialNumber());
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

    public void touch(String protocol, String identifier) {
        instances.touch(protocol, identifier);
    }

    public void markOffline(String protocol, String identifier, String sessionId) {
        instances.markOffline(protocol, identifier, sessionId);
    }

    @Transactional
    public DeviceInstance patch(String protocol, String identifier, OpsDtos.DevicePatch patch) {
        DeviceInstance device = resolve(protocol, identifier);
        return instances.setAllowWork(device.id, patch.allowWork(), patch.maxPowerPercent());
    }

    public int gunCount(Long deviceId) {
        return (int) channels.countByDeviceId(deviceId);
    }

    public OpsDtos.DeviceView view(DeviceInstance device) {
        Map<String, String> tagValues = new LinkedHashMap<>();
        for (DeviceTag tag : tags.findByDeviceIdOrderByTagKeyAsc(device.id)) {
            tagValues.put(tag.tagKey, tag.tagValue);
        }
        return new OpsDtos.DeviceView(
                device.id,
                device.deviceCode,
                device.protocol,
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
