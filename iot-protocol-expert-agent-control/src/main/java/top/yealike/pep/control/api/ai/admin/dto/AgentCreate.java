package top.yealike.pep.control.api.ai.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AgentCreate(
        @Schema(description = "Agent 唯一标识") String key,
        @Schema(description = "Agent 显示名称") String displayName,
        @Schema(description = "用途说明") String description,
        @Schema(description = "绑定的模型配置 ID") UUID modelProfileId,
        @Schema(description = "系统提示词") String systemPrompt,
        @Schema(description = "模型调用参数") Map<String, Object> modelOptions,
        @Schema(description = "Run 轮数、工具调用数和超时预算") Map<String, Object> budget,
        List<McpBindingRequest> mcpBindings,
        List<SkillBindingRequest> skillBindings) {}
