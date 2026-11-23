package top.yealike.pep.runtime.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 取消 Run 的 HTTP 请求体。 */
@Schema(name = "CancelRequest", description = "取消 Run 的请求体")
public record CancelRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long executionToken) {}
