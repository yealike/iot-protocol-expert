package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 控制面领域记录。 */
public record McpComponent(
        UUID id,
        String key,
        String displayName,
        String description,
        String origin,
        Map<String, Object> nativeRegistryRef,
        Map<String, Object> draft,
        String publishedVersion,
        long publicationGeneration,
        boolean enabled,
        long revision,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {}
