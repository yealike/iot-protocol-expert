package top.yealike.pep.simulator.integration.ops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;

/** 运维平台中允许模拟的设备档案。 */
@Schema(name = "SimulatorDevice", description = "运维平台中允许模拟的设备档案")
public record SimulatorDevice(
        Long id,
        String sn,
        String name,
        String protocol,
        String protocolDeviceId,
        int gunCount,
        boolean online,
        String state,
        boolean enabled,
        boolean allowSimulate,
        List<SimulatorConnector> connectors) {

    public SimulatorDevice {
        connectors = connectors == null
                ? List.of()
                : connectors.stream().filter(Objects::nonNull).toList();
    }

    public SimulatorDevice(
            Long id,
            String sn,
            String name,
            String protocol,
            String protocolDeviceId,
            int gunCount,
            boolean online,
            String state,
            boolean enabled,
            boolean allowSimulate) {
        this(id, sn, name, protocol, protocolDeviceId, gunCount, online, state, enabled, allowSimulate, List.of());
    }
}
