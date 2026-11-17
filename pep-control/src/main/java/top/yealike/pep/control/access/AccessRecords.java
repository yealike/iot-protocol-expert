package top.yealike.pep.control.access;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class AccessRecords {

    private AccessRecords() {}

    public record TenantInfo(UUID id, String code, String name, String state) {}

    public record ProjectInfo(UUID id, UUID tenantId, String code, String name, String state) {}

    public record MemberInfo(long userId, String username, String displayName, String state, List<String> roles) {}

    public record RoleInfo(
            UUID id, String code, String name, String scopeType, boolean builtIn, List<String> permissions) {}

    public record RoleBindingInfo(
            UUID id,
            String principalType,
            String principalId,
            UUID roleId,
            String roleCode,
            String scopeType,
            String scopeId) {}

    public record ServiceAccountInfo(UUID id, UUID projectId, String code, String name, String state) {}

    public record AccessKeyInfo(
            UUID id,
            String principalType,
            String principalId,
            String name,
            String keyPrefix,
            String state,
            OffsetDateTime expiresAt,
            OffsetDateTime lastUsedAt,
            OffsetDateTime createdAt) {}

    public record ResourceGrantInfo(
            UUID id,
            UUID ownerProjectId,
            String resourceType,
            String resourceId,
            String granteeType,
            String granteeId,
            String action,
            OffsetDateTime expiresAt) {}
}
