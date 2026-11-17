package top.yealike.pep.control.access.api;

import io.swagger.v3.oas.annotations.Operation;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.control.access.AccessAdminService;
import top.yealike.pep.control.access.AccessRecords.AccessKeyInfo;
import top.yealike.pep.control.access.AccessRecords.MemberInfo;
import top.yealike.pep.control.access.AccessRecords.ProjectInfo;
import top.yealike.pep.control.access.AccessRecords.ResourceGrantInfo;
import top.yealike.pep.control.access.AccessRecords.RoleBindingInfo;
import top.yealike.pep.control.access.AccessRecords.RoleInfo;
import top.yealike.pep.control.access.AccessRecords.ServiceAccountInfo;
import top.yealike.pep.control.access.AccessRecords.TenantInfo;

/** 身份与资源隔离管理 API：租户、项目成员、角色绑定、服务账号、访问密钥和资源共享。 */
@RestController
@RequestMapping(path = "/api/control/v1", produces = "application/json")
public class AccessAdminController {

    private final AccessAdminService access;

    public AccessAdminController(AccessAdminService access) {
        this.access = access;
    }

    public record MemberRequest(List<String> roles) {}

    public record ServiceAccountRequest(String code, String name) {}

    public record AccessKeyRequest(
            UUID serviceAccountId, String name, List<String> permissions, OffsetDateTime expiresAt) {}

    public record GrantRequest(
            String resourceType,
            String resourceId,
            String granteeType,
            String granteeId,
            String action,
            OffsetDateTime expiresAt) {}

    @Operation(summary = "列出当前账号可见的租户")
    @GetMapping("/tenants")
    public List<TenantInfo> tenants() {
        return access.tenants();
    }

    @Operation(summary = "列出租户内可见项目")
    @GetMapping("/tenants/{tenantId}/projects")
    public List<ProjectInfo> projects(@PathVariable UUID tenantId) {
        return access.projects(tenantId);
    }

    @Operation(summary = "列出项目成员及角色")
    @GetMapping("/projects/{projectId}/members")
    public List<MemberInfo> members(@PathVariable UUID projectId) {
        return access.members(projectId);
    }

    @Operation(summary = "新增或更新项目成员角色")
    @PutMapping("/projects/{projectId}/members/{userId}")
    public List<MemberInfo> putMember(
            @PathVariable UUID projectId, @PathVariable long userId, @RequestBody MemberRequest request) {
        return access.putMember(projectId, userId, request.roles());
    }

    @Operation(summary = "移除项目成员")
    @DeleteMapping("/projects/{projectId}/members/{userId}")
    public void removeMember(@PathVariable UUID projectId, @PathVariable long userId) {
        access.removeMember(projectId, userId);
    }

    @Operation(summary = "列出内置角色与权限")
    @GetMapping("/roles")
    public List<RoleInfo> roles() {
        return access.roles();
    }

    @Operation(summary = "列出指定作用域的角色绑定")
    @GetMapping("/role-bindings")
    public List<RoleBindingInfo> roleBindings(@RequestParam String scopeType, @RequestParam String scopeId) {
        return access.roleBindings(scopeType, scopeId);
    }

    @Operation(summary = "删除角色绑定")
    @DeleteMapping("/role-bindings/{id}")
    public void deleteRoleBinding(@PathVariable UUID id) {
        access.deleteRoleBinding(id);
    }

    @Operation(summary = "列出项目服务账号")
    @GetMapping("/projects/{projectId}/service-accounts")
    public List<ServiceAccountInfo> serviceAccounts(@PathVariable UUID projectId) {
        return access.serviceAccounts(projectId);
    }

    @Operation(summary = "创建项目服务账号")
    @PostMapping("/projects/{projectId}/service-accounts")
    public ServiceAccountInfo createServiceAccount(
            @PathVariable UUID projectId, @RequestBody ServiceAccountRequest request) {
        return access.createServiceAccount(projectId, request.code(), request.name());
    }

    @Operation(summary = "列出项目访问密钥（不含明文）")
    @GetMapping("/projects/{projectId}/access-keys")
    public List<AccessKeyInfo> accessKeys(@PathVariable UUID projectId) {
        return access.accessKeys(projectId);
    }

    @Operation(summary = "创建访问密钥", description = "明文密钥只在创建响应中出现一次")
    @PostMapping("/projects/{projectId}/access-keys")
    public Map<String, Object> createAccessKey(@PathVariable UUID projectId, @RequestBody AccessKeyRequest request) {
        return access.createAccessKey(
                projectId, request.serviceAccountId(), request.name(), request.permissions(), request.expiresAt());
    }

    @Operation(summary = "撤销访问密钥")
    @DeleteMapping("/projects/{projectId}/access-keys/{keyId}")
    public void revokeAccessKey(@PathVariable UUID projectId, @PathVariable UUID keyId) {
        access.revokeAccessKey(projectId, keyId);
    }

    @Operation(summary = "列出项目资源授权")
    @GetMapping("/projects/{projectId}/resource-grants")
    public List<ResourceGrantInfo> resourceGrants(@PathVariable UUID projectId) {
        return access.resourceGrants(projectId);
    }

    @Operation(summary = "创建资源授权")
    @PostMapping("/projects/{projectId}/resource-grants")
    public ResourceGrantInfo createResourceGrant(@PathVariable UUID projectId, @RequestBody GrantRequest request) {
        return access.createResourceGrant(
                projectId,
                request.resourceType(),
                request.resourceId(),
                request.granteeType(),
                request.granteeId(),
                request.action(),
                request.expiresAt());
    }

    @Operation(summary = "撤销资源授权")
    @DeleteMapping("/projects/{projectId}/resource-grants/{grantId}")
    public void deleteResourceGrant(@PathVariable UUID projectId, @PathVariable UUID grantId) {
        access.deleteResourceGrant(projectId, grantId);
    }
}
