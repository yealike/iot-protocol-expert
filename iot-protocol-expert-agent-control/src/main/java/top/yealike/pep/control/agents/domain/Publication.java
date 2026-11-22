package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.UUID;

/** 控制面领域记录。 */
public record Publication(
        UUID id,
        String resourceType,
        UUID resourceId,
        String version,
        long draftRevision,
        String state,
        String phase,
        long generation,
        String idempotencyKey,
        String errorCode,
        String errorMessage,
        int attempts,
        Instant nextAttemptAt,
        Instant deadlineAt,
        Instant createdAt,
        Instant updatedAt) {}
