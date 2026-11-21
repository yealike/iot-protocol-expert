package top.yealike.pep.ops.devices.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.pep.ops.devices.dto.DeviceDtos;
import top.yealike.pep.ops.devices.entity.DeviceChannel;
import top.yealike.pep.ops.devices.entity.DeviceInstance;
import top.yealike.pep.ops.devices.entity.DeviceProtocolMapping;
import top.yealike.pep.ops.devices.mapper.DeviceChannelMapper;
import top.yealike.pep.ops.devices.mapper.DeviceInstanceMapper;
import top.yealike.pep.ops.devices.mapper.DeviceProtocolMappingMapper;

/** 给 protocol-gateway 启动加载使用的稳定分页快照。 */
@Service
public class DeviceProtocolMappingService {

    private final DeviceProtocolMappingMapper mappings;
    private final DeviceInstanceMapper devices;
    private final DeviceChannelMapper channels;

    public DeviceProtocolMappingService(
            DeviceProtocolMappingMapper mappings, DeviceInstanceMapper devices, DeviceChannelMapper channels) {
        this.mappings = mappings;
        this.devices = devices;
        this.channels = channels;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DeviceDtos.DeviceMappingPage page(Long afterId, Integer requestedLimit) {
        long cursor = afterId == null || afterId < 0 ? 0 : afterId;
        int limit = requestedLimit == null ? 200 : Math.max(1, Math.min(requestedLimit, 500));
        List<DeviceProtocolMapping> rows = mappings.selectPage(cursor, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<DeviceProtocolMapping> pageRows = hasMore ? rows.subList(0, limit) : rows;
        if (pageRows.isEmpty()) {
            return new DeviceDtos.DeviceMappingPage(List.of(), false, null, devices.selectMappingSnapshotToken());
        }

        List<Long> deviceIds =
                pageRows.stream().map(row -> row.deviceId).distinct().toList();
        Map<Long, DeviceInstance> deviceById = new LinkedHashMap<>();
        for (DeviceInstance device : devices.findAllById(deviceIds)) {
            deviceById.put(device.id, device);
        }
        Map<Long, List<DeviceChannel>> channelByDevice = new LinkedHashMap<>();
        for (DeviceChannel channel : channels.findByDeviceIdIn(deviceIds)) {
            channelByDevice
                    .computeIfAbsent(channel.deviceId, key -> new ArrayList<>())
                    .add(channel);
        }

        List<DeviceDtos.DeviceMappingRow> content = new ArrayList<>();
        for (DeviceProtocolMapping row : pageRows) {
            DeviceInstance device = deviceById.get(row.deviceId);
            if (device == null) {
                throw new IllegalStateException("设备映射引用了不存在的设备: " + row.deviceId);
            }
            if (!row.protocol.equals(device.protocol)) {
                throw new IllegalStateException(
                        "设备映射协议与设备协议不一致: " + device.deviceCode + "/" + row.protocol + "/" + device.protocol);
            }
            List<DeviceDtos.DeviceMappingConnector> connectors =
                    channelByDevice.getOrDefault(row.deviceId, List.of()).stream()
                            .filter(channel ->
                                    channel.protocolConnectorNo != null && !channel.protocolConnectorNo.isBlank())
                            .map(channel -> new DeviceDtos.DeviceMappingConnector(
                                    channel.platformGunNo, channel.protocolConnectorNo))
                            .toList();
            content.add(new DeviceDtos.DeviceMappingRow(
                    row.id,
                    device.deviceCode,
                    row.protocol,
                    row.thirdPartyDeviceId,
                    row.enabled && device.enabled,
                    connectors));
        }
        Long nextAfterId = pageRows.get(pageRows.size() - 1).id;
        return new DeviceDtos.DeviceMappingPage(content, hasMore, nextAfterId, devices.selectMappingSnapshotToken());
    }
}
