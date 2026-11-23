package top.yealike.pep.control.api.ai.admin.dto;

import java.util.UUID;
import top.yealike.pep.runtime.contract.ModelOptions;

public record ModelUpdate(
        long expectedRevision,
        String displayName,
        String description,
        String baseUrl,
        String modelName,
        UUID credentialId,
        ModelOptions options,
        boolean enabled) {}
