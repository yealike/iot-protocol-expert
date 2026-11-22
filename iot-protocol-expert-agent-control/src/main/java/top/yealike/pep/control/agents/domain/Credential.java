package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.UUID;

/** 控制面领域记录。 */
public record Credential(
        UUID id,
        String key,
        String displayName,
        long currentSecretRevision,
        boolean enabled,
        long revision,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {}
