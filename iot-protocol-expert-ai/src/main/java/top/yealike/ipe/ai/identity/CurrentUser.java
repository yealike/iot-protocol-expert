package top.yealike.ipe.ai.identity;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import top.yealike.ipe.ai.support.AiExceptions;

/** 从安全上下文取当前身份。控制器与服务层统一从这里取，避免各处解析请求。 */
public final class CurrentUser {

    private CurrentUser() {}

    public static UserIdentity require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserIdentity identity)) {
            throw AiExceptions.unauthorized("未登录或会话已过期");
        }
        return identity;
    }
}
