package top.yealike.pep.control.api.ai.admin.dto;

import java.util.List;
import java.util.UUID;
import top.yealike.pep.control.agents.domain.AgentBudget;
import top.yealike.pep.runtime.contract.ModelOptions;

public record AgentUpdate(
        long expectedRevision,
        String displayName,
        String description,
        UUID modelProfileId,
        String systemPrompt,
        ModelOptions modelOptions,
        AgentBudget budget,
        boolean enabled,
        List<McpBindingRequest> mcpBindings,
        List<SkillBindingRequest> skillBindings) {}
