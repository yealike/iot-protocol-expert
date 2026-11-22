package top.yealike.pep.control.api.ai.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record CredentialRotate(
        @Schema(description = "新的供应商 API 密钥") String secret) {}
