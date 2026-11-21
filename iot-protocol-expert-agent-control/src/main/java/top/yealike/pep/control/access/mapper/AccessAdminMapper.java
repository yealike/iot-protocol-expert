package top.yealike.pep.control.access.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.control.access.AccessRecords.AccessKeyInfo;
import top.yealike.pep.control.access.AccessRecords.ProjectInfo;
import top.yealike.pep.control.access.AccessRecords.ResourceGrantInfo;
import top.yealike.pep.control.access.AccessRecords.RoleBindingInfo;
import top.yealike.pep.control.access.AccessRecords.ServiceAccountInfo;
import top.yealike.pep.control.access.AccessRecords.TenantInfo;

public interface AccessAdminMapper {

    record MemberRow(long userId, String username, String displayName, String state) {}

    record RolePermissionRow(UUID roleId, String permissionCode) {}

    record RoleRow(UUID id, String code, String name, String scopeType, boolean builtIn) {}

    record QuotaSubject(String id, String code, String name, String state, String context) {}

    List<QuotaSubject> findQuotaUsers(@Param("query") String query);

    List<QuotaSubject> findQuotaServiceAccounts(@Param("query") String query);

    List<TenantInfo> listTenants(@Param("userId") long userId, @Param("platformAdmin") boolean platformAdmin);

    List<ProjectInfo> listProjects(
            @Param("tenantId") UUID tenantId,
            @Param("userId") long userId,
            @Param("platformAdmin") boolean platformAdmin);

    List<MemberRow> listProjectMembers(@Param("projectId") UUID projectId);

    List<String> selectMemberRoles(@Param("userId") long userId, @Param("projectId") UUID projectId);

    int upsertProjectMember(@Param("projectId") UUID projectId, @Param("userId") long userId);

    int removeProjectMember(@Param("projectId") UUID projectId, @Param("userId") long userId);

    int deleteProjectRoleBindings(@Param("projectId") UUID projectId, @Param("userId") long userId);

    int insertProjectRoleBinding(
            @Param("projectId") UUID projectId, @Param("userId") long userId, @Param("roleCode") String roleCode);

    List<RoleRow> listRoles();

    List<RolePermissionRow> listRolePermissions();

    List<RoleBindingInfo> listRoleBindings(@Param("scopeType") String scopeType, @Param("scopeId") String scopeId);

    int deleteRoleBinding(@Param("id") UUID id);

    List<ServiceAccountInfo> listServiceAccounts(@Param("projectId") UUID projectId);

    int insertServiceAccount(
            @Param("id") UUID id,
            @Param("projectId") UUID projectId,
            @Param("code") String code,
            @Param("name") String name);

    List<AccessKeyInfo> listAccessKeys(@Param("projectId") UUID projectId);

    int insertAccessKey(
            @Param("id") UUID id,
            @Param("serviceAccountId") UUID serviceAccountId,
            @Param("name") String name,
            @Param("keyHash") String keyHash,
            @Param("keyPrefix") String keyPrefix,
            @Param("permissions") String permissions,
            @Param("expiresAt") OffsetDateTime expiresAt);

    int revokeAccessKey(@Param("id") UUID id, @Param("projectId") UUID projectId);

    List<ResourceGrantInfo> listResourceGrants(@Param("projectId") UUID projectId);

    int insertResourceGrant(
            @Param("id") UUID id,
            @Param("ownerProjectId") UUID ownerProjectId,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("granteeType") String granteeType,
            @Param("granteeId") String granteeId,
            @Param("action") String action,
            @Param("expiresAt") OffsetDateTime expiresAt);

    int deleteResourceGrant(@Param("id") UUID id, @Param("projectId") UUID projectId);
}
