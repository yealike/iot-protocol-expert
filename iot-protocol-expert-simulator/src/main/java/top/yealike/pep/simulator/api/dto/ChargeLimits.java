package top.yealike.pep.simulator.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 充电停止条件。 */
@Schema(name = "ChargeLimits", description = "充电电量、金额与时长停止条件")
public record ChargeLimits(
        @Schema(description = "充电电量上限，kWh", example = "30") double energy,
        @Schema(description = "充电金额上限", example = "50") double amount,
        @Schema(description = "充电时长上限，分钟", example = "60") int minutes) {}
