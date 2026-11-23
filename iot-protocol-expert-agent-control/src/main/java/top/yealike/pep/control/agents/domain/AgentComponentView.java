package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.UUID;
import top.yealike.pep.runtime.contract.ModelOptions;

/** Agent 草稿的 HTTP 视图。 */
public record AgentComponentView(
        UUID id,
        String key,
        String displayName,
        String description,
        UUID modelProfileId,
        String systemPrompt,
        ModelOptions modelOptions,
        AgentBudget budget,
        String publishedVersion,
        long publicationGeneration,
        boolean enabled,
        long revision,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static AgentComponentView from(AgentComponent component) {
        if (component == null) {
            return null;
        }
        return new AgentComponentView(
                component.id(),
                component.key(),
                component.displayName(),
                component.description(),
                component.modelProfileId(),
                component.systemPrompt(),
                modelOptions(component.modelOptions()),
                AgentBudget.from(component.budget()),
                component.publishedVersion(),
                component.publicationGeneration(),
                component.enabled(),
                component.revision(),
                component.createdBy(),
                component.createdAt(),
                component.updatedAt());
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
