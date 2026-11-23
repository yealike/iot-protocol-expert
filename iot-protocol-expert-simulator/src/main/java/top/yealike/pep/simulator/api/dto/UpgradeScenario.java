package top.yealike.pep.simulator.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 固件升级模拟结果。 */
@Schema(name = "UpgradeScenario", description = "固件升级模拟结果")
public record UpgradeScenario(
        @Schema(description = "模拟升级结果编码", example = "0") int result) {}
