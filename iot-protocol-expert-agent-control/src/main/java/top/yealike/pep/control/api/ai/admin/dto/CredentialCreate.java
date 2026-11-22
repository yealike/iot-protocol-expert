package top.yealike.pep.control.api.ai.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record CredentialCreate(
        @Schema(description = "凭据唯一键") String key,
        @Schema(description = "凭据显示名称") String displayName,
        @Schema(description = "供应商 API 密钥；只在本次请求中传递") String secret) {}
