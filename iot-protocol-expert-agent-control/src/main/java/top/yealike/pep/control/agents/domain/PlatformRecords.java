package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import top.yealike.pep.runtime.contract.AgentManifest;
import top.yealike.pep.runtime.contract.McpToolDescriptor;
import top.yealike.pep.runtime.contract.McpToolExecutionCategory;
import top.yealike.pep.runtime.contract.SkillBundle;

/** 控制面领域记录；JSONB 字段在存储层解析为 Map/记录。 */
public final class PlatformRecords {

    private PlatformRecords() {}

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

    public record CredentialSecret(byte[] ciphertext, byte[] nonce, String keyId, String state) {}

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

    public record McpRelease(
            UUID id,
            UUID componentId,
            String componentKey,
            String version,
            String transport,
            Map<String, Object> endpointPolicy,
            UUID credentialId,
            List<McpToolDescriptor> tools,
            String catalogDigest,
            String contentDigest,
            String state,
            long generation,
            Instant createdAt) {}

    public record McpTestReport(
            UUID id,
            UUID mcpId,
            long draftRevision,
            String configDigest,
            Map<String, Object> result,
            boolean success,
            Instant createdAt) {}

    public record McpToolPolicy(
            UUID mcpId,
            String toolName,
            McpToolExecutionCategory executionCategory,
            String reviewedBy,
            Instant reviewedAt) {}

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

    public record SkillDraftFile(
            UUID id, UUID skillId, String path, String content, String digest, Instant updatedAt) {}

    public record SkillRelease(
            UUID id,
            UUID skillId,
            String componentKey,
            String version,
            String name,
            String description,
            SkillBundle bundle,
            String bundleDigest,
            String state,
            long generation,
            Instant createdAt) {}

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

    public record AgentMcpBinding(
            UUID mcpReleaseId,
            String componentKey,
            String version,
            String toolName,
            boolean required,
            int executionOrder,
            McpToolDescriptor descriptor) {}

    public record AgentSkillBinding(
            UUID skillReleaseId,
            String componentKey,
            String version,
            int order,
            String loadMode,
            String digest,
            String name,
            String description) {}

    public record AgentRelease(
            UUID id,
            UUID agentId,
            String agentKey,
            String version,
            AgentManifest manifest,
            String manifestDigest,
            String state,
            long generation,
            long releaseCursor,
            Instant createdAt) {}

    public record Publication(
            UUID id,
            String resourceType,
            UUID resourceId,
            String version,
            long draftRevision,
            String state,
            String phase,
            long generation,
            String idempotencyKey,
            String errorCode,
            String errorMessage,
            int attempts,
            Instant nextAttemptAt,
            Instant deadlineAt,
            Instant createdAt,
            Instant updatedAt) {}

    public record RegistryProjection(
            UUID id,
            String resourceType,
            UUID releaseId,
            String serviceName,
            String providerId,
            String desiredState,
            String observedState,
            String ownerNode,
            Instant lastSuccessAt,
            String lastError) {}

    public record CompileIssue(String code, String message, String field) {}

    public record CompileResult(
            boolean valid,
            AgentManifest manifest,
            String manifestDigest,
            List<CompileIssue> errors,
            List<CompileIssue> warnings) {}

    public record AgentCatalogItem(
            String agentKey,
            String version,
            String name,
            String description,
            String status,
            int toolCount,
            int skillCount,
            List<String> skills,
            boolean healthy,
            boolean healthVerified) {}
}
