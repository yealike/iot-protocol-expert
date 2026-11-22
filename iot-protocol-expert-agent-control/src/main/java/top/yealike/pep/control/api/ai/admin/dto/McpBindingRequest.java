package top.yealike.pep.control.api.ai.admin.dto;

import java.util.UUID;

public record McpBindingRequest(
        UUID mcpReleaseId,
        String componentKey,
        String version,
        String toolName,
        Boolean required,
        Integer executionOrder) {}
