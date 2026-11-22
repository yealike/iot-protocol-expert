package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 控制面领域记录。 */
public record ModelProfile(
        UUID id,
        String key,
        String displayName,
        String description,
        String providerType,
        String baseUrl,
        String modelName,
        UUID credentialId,
        String credentialKey,
        Map<String, Object> options,
        boolean enabled,
        long revision,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {}
