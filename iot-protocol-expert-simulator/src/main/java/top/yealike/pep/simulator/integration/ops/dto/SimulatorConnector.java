package top.yealike.pep.simulator.integration.ops.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 平台枪号到第三方协议枪口的只读映射。 */
@Schema(name = "SimulatorConnector", description = "平台枪号到第三方协议枪口的映射")
public record SimulatorConnector(
        @Schema(description = "平台枪号", example = "1000000901")
        String platformGunNo,

        @Schema(description = "第三方协议枪口编号", example = "1") String protocolConnectorNo) {}
