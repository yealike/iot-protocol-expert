package top.yealike.pep.control.api.ai.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record McpPublish(
        @Schema(description = "发布版本", example = "1.0.0") String version,
        @Schema(description = "MCP 连通性测试报告 ID") UUID reportId,
        @Schema(description = "草稿版本号，用于乐观锁") long expectedRevision,
        @Schema(description = "幂等键，用于发布请求重试") String idempotencyKey) {}
