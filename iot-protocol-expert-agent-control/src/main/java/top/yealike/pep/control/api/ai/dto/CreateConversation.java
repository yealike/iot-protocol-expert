package top.yealike.pep.control.api.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record CreateConversation(
        @Schema(description = "Agent 唯一标识", example = "protocol-expert")
        String agentKey,

        @Schema(description = "指定已发布版本；省略时使用当前可用版本", example = "1.0.0")
        String version,

        @Schema(description = "会话标题；省略时由服务端生成") String title) {}
