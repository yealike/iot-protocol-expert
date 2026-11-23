package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.UUID;

/** MCP 草稿的 HTTP 视图。 */
public record McpComponentView(
        UUID id,
        String key,
        String displayName,
        String description,
        String origin,
        McpNativeRegistryRef nativeRegistryRef,
        McpDraft draft,
        String publishedVersion,
        long publicationGeneration,
        boolean enabled,
        long revision,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static McpComponentView from(McpComponent component) {
        if (component == null) {
            return null;
        }
        return new McpComponentView(
                component.id(),
                component.key(),
                component.displayName(),
                component.description(),
                component.origin(),
                McpNativeRegistryRef.from(component.nativeRegistryRef()),
                McpDraft.from(component.draft()),
                component.publishedVersion(),
                component.publicationGeneration(),
                component.enabled(),
                component.revision(),
                component.createdBy(),
                component.createdAt(),
                component.updatedAt());
    }
}
