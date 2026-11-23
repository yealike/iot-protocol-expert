package top.yealike.pep.modelgateway.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 登记逻辑模型的 HTTP 请求体。 */
@Schema(name = "ModelRequest", description = "登记逻辑模型的请求体")
public record ModelRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String key,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String providerCode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String upstreamModel,
        Integer contextWindow,
        Integer maxOutputTokens,
        String countingMode) {}
