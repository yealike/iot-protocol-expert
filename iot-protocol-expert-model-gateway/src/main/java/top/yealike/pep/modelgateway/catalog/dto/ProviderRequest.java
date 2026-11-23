package top.yealike.pep.modelgateway.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 登记模型供应商的 HTTP 请求体；apiKey 只写不读。 */
@Schema(name = "ProviderRequest", description = "登记模型供应商的请求体；apiKey 只写不读")
public record ProviderRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String baseUrl,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "password")
        String apiKey) {}
