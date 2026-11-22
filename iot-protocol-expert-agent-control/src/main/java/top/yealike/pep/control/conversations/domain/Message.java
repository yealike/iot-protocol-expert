package top.yealike.pep.control.conversations.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record Message(
        UUID id,
        UUID conversationId,
        UUID runId,
        String role,
        String content,
        List<Citation> citations,
        String status,
        long seq,
        Instant createdAt,
        Map<String, Object> usage) {}
