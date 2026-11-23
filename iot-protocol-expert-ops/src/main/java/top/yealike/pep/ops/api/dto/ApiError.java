package top.yealike.pep.ops.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ApiError", description = "运营平台统一错误响应")
public record ApiError(
        @Schema(description = "稳定错误码", example = "BAD_REQUEST")
        String error,

        @Schema(description = "可直接展示的错误说明", example = "请求参数无效")
        String message) {}
