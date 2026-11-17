package top.yealike.pep.control.access;

import java.util.UUID;
import org.springframework.stereotype.Service;
import top.yealike.pep.control.access.mapper.AccessMapper;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.runtime.contract.UserIdentity;

/**
 * 项目作用域授权。角色权限按 PLATFORM / TENANT / PROJECT 绑定范围求值，
 * 独立于 Spring Security 的菜单级权限集合。
 */
@Service
public class AccessService {

    private final AccessMapper access;

    public AccessService(AccessMapper access) {
        this.access = access;
    }

    public record Scope(UserIdentity actor, long userId, UUID tenantId, UUID projectId) {}

    public Scope requireProject(UUID projectId) {
        return requireProject(projectId, null);
    }

    public Scope requireProject(UUID projectId, String permission) {
        UserIdentity actor = CurrentUser.require();
        long userId = userId(actor);
        if (!access.isActiveUser(userId)) {
            throw AiExceptions.unauthorized("账号已停用");
        }
        AccessMapper.ProjectScope project = access.findProject(projectId);
        if (project == null) {
            throw AiExceptions.notFound("项目不存在");
        }
        if (!"ACTIVE".equals(project.projectState()) || !"ACTIVE".equals(project.tenantState())) {
            throw AiExceptions.forbidden("SCOPE_INACTIVE", "项目或租户已停用");
        }
        if (!access.isProjectMember(projectId, userId) && !access.isTenantMember(project.tenantId(), userId)) {
            throw AiExceptions.forbidden("PROJECT_FORBIDDEN", "不属于该项目");
        }
        if (permission != null && !access.hasPermission(userId, permission, project.tenantId(), project.projectId())) {
            throw AiExceptions.forbidden("PERMISSION_DENIED", "缺少权限：" + permission);
        }
        return new Scope(actor, userId, project.tenantId(), project.projectId());
    }

    public Scope requireTask(UUID projectId, String ownerId, String permission) {
        Scope scope = requireProject(projectId);
        if (!ownerId.equals(scope.actor().id()) && !hasPermission(scope, permission)) {
            throw AiExceptions.forbidden("TASK_FORBIDDEN", "调查任务默认私有，缺少共享授权");
        }
        return scope;
    }

    public boolean canReadAll(UserIdentity actor, UUID tenantId, UUID projectId) {
        long userId = userId(actor);
        return access.isActiveUser(userId) && access.hasPermission(userId, "investigation:read", tenantId, projectId);
    }

    public boolean hasPermission(Scope scope, String permission) {
        return permission != null
                && access.hasPermission(scope.userId(), permission, scope.tenantId(), scope.projectId());
    }

    public boolean hasPlatformPermission(UserIdentity actor, String permission) {
        return access.isActiveUser(userId(actor)) && access.hasPlatformPermission(userId(actor), permission);
    }

    public UserIdentity requirePlatformPermission(String permission) {
        UserIdentity actor = CurrentUser.require();
        long userId = userId(actor);
        if (!access.isActiveUser(userId)) {
            throw AiExceptions.unauthorized("账号已停用");
        }
        if (!access.hasPlatformPermission(userId, permission)) {
            throw AiExceptions.forbidden("PERMISSION_DENIED", "缺少平台权限：" + permission);
        }
        return actor;
    }

    public Scope requireTenant(UUID tenantId, String permission) {
        UserIdentity actor = CurrentUser.require();
        long userId = userId(actor);
        if (!access.isActiveUser(userId)) {
            throw AiExceptions.unauthorized("账号已停用");
        }
        boolean platformAdmin = access.hasPlatformPermission(userId, "platform:admin");
        if (!platformAdmin && !access.isTenantMember(tenantId, userId)) {
            throw AiExceptions.forbidden("TENANT_FORBIDDEN", "不属于该租户");
        }
        if (permission != null && !platformAdmin && !access.hasPermission(userId, permission, tenantId, null)) {
            throw AiExceptions.forbidden("PERMISSION_DENIED", "缺少权限：" + permission);
        }
        return new Scope(actor, userId, tenantId, null);
    }

    static long userId(UserIdentity actor) {
        try {
            return Long.parseLong(actor.id());
        } catch (NumberFormatException exception) {
            throw AiExceptions.unauthorized("当前身份不是本地应用账号");
        }
    }
}
