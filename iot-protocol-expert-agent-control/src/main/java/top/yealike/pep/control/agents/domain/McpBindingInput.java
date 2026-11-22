package top.yealike.pep.control.agents.domain;

import java.util.UUID;

public record McpBindingInput(UUID mcpReleaseId, String toolName, boolean required, int executionOrder) {}
