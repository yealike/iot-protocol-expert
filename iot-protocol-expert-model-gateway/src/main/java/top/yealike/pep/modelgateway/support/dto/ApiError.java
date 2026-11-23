package top.yealike.pep.modelgateway.support.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 模型网关统一错误响应；错误码稳定，message 可直接展示。 */
@Schema(name = "ApiError", description = "模型网关统一错误响应")
public record ApiError(String error, String message) {}
