package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SimulatorConnectorView", description = "模拟器使用的充电枪接入映射")
public record SimulatorConnectorView(
        @Schema(description = "平台 10 位枪号，由 8 位设备 SN 和 2 位枪号组成")
        String platformGunNo,

        @Schema(description = "第三方协议中的充电枪编号") String protocolConnectorNo) {}
