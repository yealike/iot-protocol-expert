package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 控制面领域记录。 */
public record McpTestReport(
        UUID id,
        UUID mcpId,
        long draftRevision,
        String configDigest,
        Map<String, Object> result,
        boolean success,
        Instant createdAt) {}
