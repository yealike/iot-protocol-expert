package top.yealike.pep.ops.api.simulator.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "模拟器启动充电")
public record SimulatorStartCommand(
        @Schema(description = "平台设备 ID") Long deviceId,
        @Schema(description = "枪号") String gunNo,
        @Schema(description = "虚拟卡号") String idTag,
        @Schema(description = "物理卡号") String physicalCard,
        @Schema(description = "账户余额") String balance) {}
