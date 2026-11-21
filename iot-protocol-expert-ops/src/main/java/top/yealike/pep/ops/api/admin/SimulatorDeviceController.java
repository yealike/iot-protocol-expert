package top.yealike.pep.ops.api.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.*;
import top.yealike.pep.ops.devices.dto.DeviceDtos;
import top.yealike.pep.ops.devices.entity.DeviceChannel;
import top.yealike.pep.ops.devices.entity.DeviceInstance;
import top.yealike.pep.ops.devices.entity.DeviceProtocolMapping;
import top.yealike.pep.ops.devices.mapper.DeviceChannelMapper;
import top.yealike.pep.ops.devices.mapper.DeviceProtocolMappingMapper;
import top.yealike.pep.ops.devices.service.DeviceInstanceService;
import top.yealike.pep.ops.devices.service.DeviceService;

/** 模拟器只读设备目录。对外仍使用第三方协议编号，平台主键保持 8 位设备 SN。 */
@RestController
@RequestMapping("/api/ops/simulator/devices")
public class SimulatorDeviceController {

    private final DeviceService devices;
    private final DeviceInstanceService instances;
    private final DeviceChannelMapper channels;
    private final DeviceProtocolMappingMapper mappings;

    public SimulatorDeviceController(
            DeviceService devices,
            DeviceInstanceService instances,
            DeviceChannelMapper channels,
            DeviceProtocolMappingMapper mappings) {
        this.devices = devices;
        this.instances = instances;
        this.channels = channels;
        this.mappings = mappings;
    }

    @GetMapping
    public List<DeviceDtos.SimulatorDeviceView> list(
            @RequestParam(required = false) String sn,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String protocolDeviceId) {
        List<DeviceInstance> candidates = devices.simulatorDevices().stream()
                .filter(device -> sn == null
                        || sn.isBlank()
                        || device.deviceCode
                                .toLowerCase(java.util.Locale.ROOT)
                                .contains(sn.toLowerCase(java.util.Locale.ROOT)))
                .filter(device -> protocol == null
                        || protocol.isBlank()
                        || DeviceService.normalizeProtocol(device.protocol)
                                .equals(DeviceService.normalizeProtocol(protocol)))
                .toList();
        Map<Long, String> protocolDeviceIds = protocolDeviceIds(candidates);
        if (protocolDeviceId != null && !protocolDeviceId.isBlank()) {
            candidates = candidates.stream()
                    .filter(device ->
                            protocolDeviceIds.getOrDefault(device.id, "").contains(protocolDeviceId))
                    .toList();
        }

        Map<Long, DeviceInstance> deviceById =
                candidates.stream().collect(Collectors.toMap(device -> device.id, Function.identity()));
        List<DeviceDtos.DeviceRow> views = instances.rows(candidates);
        Map<Long, List<DeviceDtos.SimulatorConnectorView>> connectors = connectors(candidates);
        return views.stream()
                .map(view -> row(
                        view,
                        deviceById.get(view.id()),
                        protocolDeviceIds.get(view.id()),
                        connectors.getOrDefault(view.id(), List.of())))
                .toList();
    }

    @GetMapping("/{id}")
    public DeviceDtos.SimulatorDeviceView get(@PathVariable Long id) {
        DeviceInstance device = devices.get(id);
        if (!device.enabled || !device.allowSimulate) {
            throw new IllegalArgumentException("充电桩不可用于模拟: " + device.deviceCode);
        }
        String protocolDeviceId = mappings.findByDeviceId(id)
                .map(mapping -> mapping.thirdPartyDeviceId)
                .orElse(null);
        List<DeviceDtos.SimulatorConnectorView> connectors = channels.findByDeviceIdOrderByChannelNoAsc(id).stream()
                .filter(SimulatorDeviceController::hasProtocolConnector)
                .map(SimulatorDeviceController::connector)
                .toList();
        return row(instances.view(device), device, protocolDeviceId, connectors);
    }

    private Map<Long, String> protocolDeviceIds(List<DeviceInstance> devices) {
        if (devices.isEmpty()) {
            return Map.of();
        }
        List<Long> deviceIds = devices.stream().map(device -> device.id).toList();
        Map<Long, String> result = new LinkedHashMap<>();
        for (DeviceProtocolMapping mapping : mappings.selectByDeviceIds(deviceIds)) {
            result.put(mapping.deviceId, mapping.thirdPartyDeviceId);
        }
        return result;
    }

    private Map<Long, List<DeviceDtos.SimulatorConnectorView>> connectors(List<DeviceInstance> devices) {
        if (devices.isEmpty()) {
            return Map.of();
        }
        List<Long> deviceIds = devices.stream().map(device -> device.id).toList();
        Map<Long, List<DeviceDtos.SimulatorConnectorView>> result = new LinkedHashMap<>();
        for (DeviceChannel channel : channels.findByDeviceIdIn(deviceIds)) {
            if (!hasProtocolConnector(channel)) {
                continue;
            }
            result.computeIfAbsent(channel.deviceId, key -> new ArrayList<>()).add(connector(channel));
        }
        return result;
    }

    private static boolean hasProtocolConnector(DeviceChannel channel) {
        return channel.protocolConnectorNo != null && !channel.protocolConnectorNo.isBlank();
    }

    private static DeviceDtos.SimulatorConnectorView connector(DeviceChannel channel) {
        return new DeviceDtos.SimulatorConnectorView(channel.platformGunNo, channel.protocolConnectorNo);
    }

    private static DeviceDtos.SimulatorDeviceView row(
            DeviceDtos.DeviceRow view,
            DeviceInstance device,
            String protocolDeviceId,
            List<DeviceDtos.SimulatorConnectorView> connectors) {
        return new DeviceDtos.SimulatorDeviceView(
                view.id(),
                view.deviceSn(),
                view.name(),
                view.protocol(),
                protocolDeviceId,
                view.gunCount(),
                "ONLINE".equals(view.state()) || "FAULT".equals(view.state()),
                view.state(),
                view.enabled(),
                device.allowSimulate,
                connectors);
    }
}
