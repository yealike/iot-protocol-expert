package top.yealike.pep.control.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.pep.control.access.AccessRecords.AccessKeyInfo;
import top.yealike.pep.control.access.AccessRecords.MemberInfo;
import top.yealike.pep.control.access.AccessRecords.ProjectInfo;
import top.yealike.pep.control.access.AccessRecords.ResourceGrantInfo;
import top.yealike.pep.control.access.AccessRecords.RoleBindingInfo;
import top.yealike.pep.control.access.AccessRecords.RoleInfo;
import top.yealike.pep.control.access.AccessRecords.ServiceAccountInfo;
import top.yealike.pep.control.access.AccessRecords.TenantInfo;
import top.yealike.pep.control.access.mapper.AccessAdminMapper;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.control.support.JsonSupport;

/** 租户、项目成员、角色绑定、服务账号、访问密钥与资源共享管理。 */
@Service
public class AccessAdminService {

    private static final String ALL = "*";
    private final AccessAdminMapper mapper;
    private final AccessService access;
    private final SecureRandom random = new SecureRandom();

    public AccessAdminService(AccessAdminMapper mapper, AccessService access) {
        this.mapper = mapper;
        this.access = access;
    }

    public List<TenantInfo> tenants() {
        UserIdentity actor = CurrentUser.require();
        long userId = AccessService.userId(actor);
        boolean platformAdmin = access.hasPlatformPermission(actor, "platform:admin");
        return mapper.listTenants(userId, platformAdmin);
    }

    public List<ProjectInfo> projects(UUID tenantId) {
        UserIdentity actor = CurrentUser.require();
        long userId = AccessService.userId(actor);
        boolean platformAdmin = access.hasPlatformPermission(actor, "platform:admin");
        return mapper.listProjects(tenantId, userId, platformAdmin);
    }

    public List<MemberInfo> members(UUID projectId) {
        access.requireProject(projectId);
        return mapper.listProjectMembers(projectId).stream()
                .map(row -> new MemberInfo(
                        row.userId(),
                        row.username(),
                        row.displayName(),
                        row.state(),
                        mapper.selectMemberRoles(row.userId(), projectId)))
                .toList();
    }

    @Transactional
    public List<MemberInfo> putMember(UUID projectId, long userId, List<String> roleCodes) {
        access.requireProject(projectId, "member:manage");
        List<String> requested = roleCodes == null
                ? List.of()
                : roleCodes.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(value -> value.strip().toUpperCase(java.util.Locale.ROOT))
                        .distinct()
                        .toList();
        Set<String> projectRoles = mapper.listRoles().stream()
                .filter(role -> "PROJECT".equals(role.scopeType()))
                .map(AccessAdminMapper.RoleRow::code)
                .collect(Collectors.toSet());
        if (!projectRoles.containsAll(requested)) {
            throw AiExceptions.badRequest("包含非项目级角色");
        }
        mapper.upsertProjectMember(projectId, userId);
        mapper.deleteProjectRoleBindings(projectId, userId);
        for (String roleCode : requested) {
            mapper.insertProjectRoleBinding(projectId, userId, roleCode);
        }
        return members(projectId);
    }

    @Transactional
    public void removeMember(UUID projectId, long userId) {
        access.requireProject(projectId, "member:manage");
        mapper.deleteProjectRoleBindings(projectId, userId);
        mapper.removeProjectMember(projectId, userId);
    }

    public List<RoleInfo> roles() {
        CurrentUser.require();
        Map<UUID, List<String>> permissions = mapper.listRolePermissions().stream()
                .collect(Collectors.groupingBy(
                        AccessAdminMapper.RolePermissionRow::roleId,
                        Collectors.mapping(AccessAdminMapper.RolePermissionRow::permissionCode, Collectors.toList())));
        return mapper.listRoles().stream()
                .map(role -> new RoleInfo(
                        role.id(),
                        role.code(),
                        role.name(),
                        role.scopeType(),
                        role.builtIn(),
                        permissions.getOrDefault(role.id(), List.of())))
                .toList();
    }

    public List<RoleBindingInfo> roleBindings(String scopeType, String scopeId) {
        String type = scopeType == null ? "" : scopeType.strip().toUpperCase(java.util.Locale.ROOT);
        if ("PROJECT".equals(type)) {
            access.requireProject(UUID.fromString(scopeId), "member:manage");
        } else if ("TENANT".equals(type)) {
            access.requireTenant(UUID.fromString(scopeId), "tenant:manage");
        } else if (!"PLATFORM".equals(type) || !ALL.equals(scopeId)) {
            throw AiExceptions.badRequest("作用域无效");
        } else {
            access.requirePlatformPermission("platform:admin");
        }
        return mapper.listRoleBindings(type, scopeId);
    }

    @Transactional
    public void deleteRoleBinding(UUID id) {
        access.requirePlatformPermission("platform:admin");
        mapper.deleteRoleBinding(id);
    }

    public List<ServiceAccountInfo> serviceAccounts(UUID projectId) {
        access.requireProject(projectId);
        return mapper.listServiceAccounts(projectId);
    }

    @Transactional
    public ServiceAccountInfo createServiceAccount(UUID projectId, String code, String name) {
        access.requireProject(projectId, "project:manage");
        String normalizedCode = requireCode(code);
        String normalizedName = requireText(name, "服务账号名称", 128);
        UUID id = UUID.randomUUID();
        mapper.insertServiceAccount(id, projectId, normalizedCode, normalizedName);
        return mapper.listServiceAccounts(projectId).stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    public List<AccessKeyInfo> accessKeys(UUID projectId) {
        access.requireProject(projectId);
        return mapper.listAccessKeys(projectId);
    }

    @Transactional
    public Map<String, Object> createAccessKey(
            UUID projectId, UUID serviceAccountId, String name, List<String> permissions, OffsetDateTime expiresAt) {
        access.requireProject(projectId, "project:manage");
        ServiceAccountInfo account = mapper.listServiceAccounts(projectId).stream()
                .filter(item -> item.id().equals(serviceAccountId))
                .findFirst()
                .orElseThrow(() -> AiExceptions.notFound("服务账号不存在"));
        if (!"ACTIVE".equals(account.state())) {
            throw AiExceptions.conflict("SERVICE_ACCOUNT_INACTIVE", "服务账号已停用");
        }
        if (expiresAt != null && !expiresAt.isAfter(OffsetDateTime.now())) {
            throw AiExceptions.badRequest("访问密钥有效期必须晚于当前时间");
        }
        byte[] material = new byte[32];
        random.nextBytes(material);
        String plaintext = "pep_" + account.code() + "_"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(material);
        String prefix = plaintext.substring(0, Math.min(16, plaintext.length()));
        UUID id = UUID.randomUUID();
        mapper.insertAccessKey(
                id,
                serviceAccountId,
                requireText(name, "访问密钥名称", 64),
                sha256(plaintext),
                prefix,
                JsonSupport.write(permissions == null ? List.of() : permissions),
                expiresAt);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id.toString());
        body.put("key", plaintext);
        body.put("prefix", prefix);
        body.put("note", "明文密钥只返回一次，请立即保存");
        return body;
    }

    @Transactional
    public void revokeAccessKey(UUID projectId, UUID keyId) {
        access.requireProject(projectId, "project:manage");
        if (mapper.revokeAccessKey(keyId, projectId) == 0) {
            throw AiExceptions.notFound("访问密钥不存在或已撤销");
        }
    }

    public List<ResourceGrantInfo> resourceGrants(UUID projectId) {
        access.requireProject(projectId);
        return mapper.listResourceGrants(projectId);
    }

    @Transactional
    public ResourceGrantInfo createResourceGrant(
            UUID projectId,
            String resourceType,
            String resourceId,
            String granteeType,
            String granteeId,
            String action,
            OffsetDateTime expiresAt) {
        access.requireProject(projectId, "project:manage");
        if (expiresAt != null && !expiresAt.isAfter(OffsetDateTime.now())) {
            throw AiExceptions.badRequest("共享有效期必须晚于当前时间");
        }
        UUID id = UUID.randomUUID();
        mapper.insertResourceGrant(
                id,
                projectId,
                requireText(resourceType, "资源类型", 32).toUpperCase(java.util.Locale.ROOT),
                requireText(resourceId, "资源 ID", 64),
                requireText(granteeType, "被授权主体类型", 16).toUpperCase(java.util.Locale.ROOT),
                requireText(granteeId, "被授权主体 ID", 64),
                requireText(action, "动作", 32),
                expiresAt);
        return mapper.listResourceGrants(projectId).stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public void deleteResourceGrant(UUID projectId, UUID grantId) {
        access.requireProject(projectId, "project:manage");
        mapper.deleteResourceGrant(grantId, projectId);
    }

    private static String requireCode(String value) {
        String normalized = value == null ? "" : value.strip();
        if (!normalized.matches("[a-zA-Z0-9_.-]{2,64}")) {
            throw AiExceptions.badRequest("编码须为 2 至 64 位字母、数字、点、下划线或短横线");
        }
        return normalized;
    }

    private static String requireText(String value, String field, int max) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw AiExceptions.badRequest(field + "不能为空");
        }
        if (normalized.length() > max) {
            throw AiExceptions.badRequest(field + "不能超过 " + max + " 字符");
        }
        return normalized;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
