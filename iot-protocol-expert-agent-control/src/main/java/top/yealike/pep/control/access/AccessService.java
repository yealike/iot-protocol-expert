package top.yealike.pep.control.access;

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

    static long userId(UserIdentity actor) {
        try {
            return Long.parseLong(actor.id());
        } catch (NumberFormatException exception) {
            throw AiExceptions.unauthorized("当前身份不是本地应用账号");
        }
    }
}
