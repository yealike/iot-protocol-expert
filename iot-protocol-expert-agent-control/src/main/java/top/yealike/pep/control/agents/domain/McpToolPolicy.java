package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.UUID;
import top.yealike.pep.runtime.contract.McpToolExecutionCategory;

/** 控制面领域记录。 */
public record McpToolPolicy(
        UUID mcpId,
        String toolName,
        McpToolExecutionCategory executionCategory,
        String reviewedBy,
        Instant reviewedAt) {}
