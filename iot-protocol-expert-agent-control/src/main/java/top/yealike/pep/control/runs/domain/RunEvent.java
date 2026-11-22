package top.yealike.pep.control.runs.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RunEvent(
        long cursor, UUID conversationId, UUID runId, String type, Map<String, Object> payload, Instant createdAt) {}
