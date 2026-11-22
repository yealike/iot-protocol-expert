package top.yealike.pep.control.api.ai.admin.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AgentUpdate(
        long expectedRevision,
        String displayName,
        String description,
        UUID modelProfileId,
        String systemPrompt,
        Map<String, Object> modelOptions,
        Map<String, Object> budget,
        boolean enabled,
        List<McpBindingRequest> mcpBindings,
        List<SkillBindingRequest> skillBindings) {}
