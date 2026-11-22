package top.yealike.pep.control.api.ai.admin.dto;

import java.util.Map;
import java.util.UUID;

public record ModelUpdate(
        long expectedRevision,
        String displayName,
        String description,
        String baseUrl,
        String modelName,
        UUID credentialId,
        Map<String, Object> options,
        boolean enabled) {}
