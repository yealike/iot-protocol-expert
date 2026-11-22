package top.yealike.pep.control.runs.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ToolCall(
        UUID id,
        UUID runId,
        UUID conversationId,
        String toolName,
        String status,
        String decision,
        String denyReason,
        Map<String, Object> argsSummary,
        Map<String, Object> resultSummary,
        Integer durationMs,
        String traceId,
        Instant createdAt,
        Instant finishedAt) {}
