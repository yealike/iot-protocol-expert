package top.yealike.pep.control.runs.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RunView(
        UUID id,
        UUID conversationId,
        String question,
        String agentKey,
        String agentVersion,
        String manifestDigest,
        String traceId,
        String status,
        int priority,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        List<ToolCall> toolCalls) {}
