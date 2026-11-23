package top.yealike.pep.ops.api.simulator;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.*;
import top.yealike.pep.ops.devices.domain.DeviceChannel;
import top.yealike.pep.ops.devices.domain.DeviceInstance;
import top.yealike.pep.ops.devices.domain.DeviceProtocolMapping;
import top.yealike.pep.ops.devices.dto.DeviceRow;
import top.yealike.pep.ops.devices.dto.SimulatorConnectorView;
import top.yealike.pep.ops.devices.dto.SimulatorDeviceView;
import top.yealike.pep.ops.devices.mapper.DeviceChannelMapper;
import top.yealike.pep.ops.devices.mapper.DeviceProtocolMappingMapper;
import top.yealike.pep.ops.devices.service.DeviceInstanceService;
import top.yealike.pep.ops.devices.service.DeviceService;

/** 模拟器只读设备目录。对外仍使用第三方协议编号，平台主键保持 8 位设备 SN。 */
@RestController
@RequestMapping(path = "/api/ops/simulator/devices", produces = "application/json")
@Tag(name = "模拟器-设备目录", description = "供模拟器读取已启用且允许模拟的设备档案")
@SecurityRequirement(name = "simulatorToken")
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
    @Operation(operationId = "listSimulatorDirectoryDevices", summary = "查询可模拟设备目录")
    public List<SimulatorDeviceView> list(
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
        List<DeviceRow> views = instances.rows(candidates);
        Map<Long, List<SimulatorConnectorView>> connectors = connectors(candidates);
        return views.stream()
                .map(view -> row(
                        view,
                        deviceById.get(view.id()),
                        protocolDeviceIds.get(view.id()),
                        connectors.getOrDefault(view.id(), List.of())))
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getSimulatorDirectoryDevice", summary = "查询单台可模拟设备")
    public SimulatorDeviceView get(@PathVariable Long id) {
        DeviceInstance device = devices.get(id);
        if (!device.enabled || !device.allowSimulate) {
            throw new IllegalArgumentException("充电桩不可用于模拟: " + device.deviceCode);
        }
        String protocolDeviceId = mappings.findByDeviceId(id)
                .map(mapping -> mapping.thirdPartyDeviceId)
                .orElse(null);
        List<SimulatorConnectorView> connectors = channels.findByDeviceIdOrderByChannelNoAsc(id).stream()
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

    private Map<Long, List<SimulatorConnectorView>> connectors(List<DeviceInstance> devices) {
        if (devices.isEmpty()) {
            return Map.of();
        }
        List<Long> deviceIds = devices.stream().map(device -> device.id).toList();
        Map<Long, List<SimulatorConnectorView>> result = new LinkedHashMap<>();
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

    private static SimulatorConnectorView connector(DeviceChannel channel) {
        return new SimulatorConnectorView(channel.platformGunNo, channel.protocolConnectorNo);
    }

    private static SimulatorDeviceView row(
            DeviceRow view, DeviceInstance device, String protocolDeviceId, List<SimulatorConnectorView> connectors) {
        return new SimulatorDeviceView(
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
