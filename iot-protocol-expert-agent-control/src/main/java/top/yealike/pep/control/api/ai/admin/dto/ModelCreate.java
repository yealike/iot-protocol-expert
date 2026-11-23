package top.yealike.pep.control.api.ai.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import top.yealike.pep.runtime.contract.ModelOptions;

public record ModelCreate(
        @Schema(description = "模型配置唯一键") String key,
        @Schema(description = "模型显示名称") String displayName,
        @Schema(description = "用途说明") String description,
        @Schema(description = "OpenAI 兼容 API 根地址") String baseUrl,
        @Schema(description = "供应商模型名称") String modelName,
        @Schema(description = "关联的凭据 ID") UUID credentialId,
        @Schema(description = "模型调用参数") ModelOptions options) {}
