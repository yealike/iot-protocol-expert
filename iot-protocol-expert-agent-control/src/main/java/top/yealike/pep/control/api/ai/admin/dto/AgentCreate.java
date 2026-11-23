package top.yealike.pep.control.api.ai.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import top.yealike.pep.control.agents.domain.AgentBudget;
import top.yealike.pep.runtime.contract.ModelOptions;

public record AgentCreate(
        @Schema(description = "Agent 唯一标识") String key,
        @Schema(description = "Agent 显示名称") String displayName,
        @Schema(description = "用途说明") String description,
        @Schema(description = "绑定的模型配置 ID") UUID modelProfileId,
        @Schema(description = "系统提示词") String systemPrompt,
        @Schema(description = "模型调用参数") ModelOptions modelOptions,
        @Schema(description = "Run 轮数、工具调用数和超时预算") AgentBudget budget,
        List<McpBindingRequest> mcpBindings,
        List<SkillBindingRequest> skillBindings) {}
