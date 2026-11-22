package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.UUID;

/** 控制面领域记录。 */
public record SkillComponent(
        UUID id,
        String key,
        String displayName,
        String description,
        long draftRevision,
        String publishedVersion,
        long publicationGeneration,
        boolean enabled,
        long revision,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {}
