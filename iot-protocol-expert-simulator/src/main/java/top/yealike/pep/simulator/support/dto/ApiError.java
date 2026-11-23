package top.yealike.pep.simulator.support.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 模拟器统一错误响应。 */
@Schema(name = "ApiError", description = "模拟器统一错误响应")
public record ApiError(
        @Schema(description = "稳定错误码", example = "INVALID_ARGUMENT")
        String code,

        @Schema(description = "可直接展示的错误说明", example = "枪号无效")
        String message,

        @Schema(description = "错误发生时间", example = "2026-11-23 10:00:00")
        String time) {}
