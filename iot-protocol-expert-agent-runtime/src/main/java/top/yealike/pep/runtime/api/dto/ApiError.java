package top.yealike.pep.runtime.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Runtime 统一错误响应；错误码稳定，message 可直接展示。 */
@Schema(name = "ApiError", description = "Runtime 统一错误响应")
public record ApiError(String error, String message) {}
