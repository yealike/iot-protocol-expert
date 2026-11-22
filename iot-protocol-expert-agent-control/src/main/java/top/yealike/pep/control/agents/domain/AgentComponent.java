package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 控制面领域记录。 */
public record AgentComponent(
        UUID id,
        String key,
        String displayName,
        String description,
        UUID modelProfileId,
        String systemPrompt,
        Map<String, Object> modelOptions,
        Map<String, Object> budget,
        String publishedVersion,
        long publicationGeneration,
        boolean enabled,
        long revision,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {}
