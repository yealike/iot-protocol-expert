package top.yealike.pep.gateway.support.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 协议通道统一错误响应。 */
@Schema(name = "ApiError", description = "协议通道统一错误响应")
public record ApiError(
        @Schema(description = "稳定错误码", example = "BAD_REQUEST")
        String error,

        @Schema(description = "可直接展示的错误说明", example = "请求参数无效")
        String message) {}
