package top.yealike.pep.ops.api.simulator.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "模拟器停止充电")
public record SimulatorStopCommand(
        @Schema(description = "平台设备 ID") Long deviceId,
        @Schema(description = "枪号") String gunNo) {}
