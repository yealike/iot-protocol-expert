package top.yealike.pep.control.runs.domain;

import java.time.Instant;
import java.util.UUID;

public record RuntimeEventInsert(
        UUID conversationId,
        UUID runId,
        long cursor,
        String type,
        String payloadJson,
        Instant createdAt,
        long executionToken,
        long sourceSeq,
        UUID eventId) {}
