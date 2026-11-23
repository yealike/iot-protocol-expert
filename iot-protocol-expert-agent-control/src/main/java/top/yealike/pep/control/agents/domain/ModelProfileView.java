package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.UUID;
import top.yealike.pep.runtime.contract.ModelOptions;

/** 逻辑模型配置视图。 */
public record ModelProfileView(
        UUID id,
        String key,
        String displayName,
        String description,
        String providerType,
        String baseUrl,
        String modelName,
        UUID credentialId,
        String credentialKey,
        ModelOptions options,
        boolean enabled,
        long revision,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static ModelProfileView from(ModelProfile profile) {
        if (profile == null) {
            return null;
        }
        return new ModelProfileView(
                profile.id(),
                profile.key(),
                profile.displayName(),
                profile.description(),
                profile.providerType(),
                profile.baseUrl(),
                profile.modelName(),
                profile.credentialId(),
                profile.credentialKey(),
                modelOptions(profile.options()),
                profile.enabled(),
                profile.revision(),
                profile.createdBy(),
                profile.createdAt(),
                profile.updatedAt());
    }

    private static ModelOptions modelOptions(java.util.Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return new ModelOptions(
                decimal(values.get("temperature")),
                integer(values.get("maxTokens")),
                integer(values.get("contextWindow")));
    }

    private static Double decimal(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}
