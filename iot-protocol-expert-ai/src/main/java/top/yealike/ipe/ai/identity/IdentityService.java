package top.yealike.ipe.ai.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import top.yealike.ipe.ai.gateway.McpGateway;
import top.yealike.ipe.ai.support.AiExceptions;

/**
 * 身份校验：把浏览器的会话 cookie 转发给 ops 认证入口，用返回值建立本服务身份。
 *
 * <p>AI 服务不自行签发身份、不解析前端角色头。ops 是唯一可信身份来源，网关认证也不替代这一步。
 */
@Service
public class IdentityService {

    private final McpGateway gateway;

    public IdentityService(McpGateway gateway) {
        this.gateway = gateway;
    }

    public UserIdentity resolve(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            throw AiExceptions.unauthorized("未登录或会话已过期");
        }
        return verify(cookieHeader);
    }

    /** 任务执行期间不保存用户 cookie，使用服务身份向 ops 读取最新账号状态。 */
    public UserIdentity resolveActor(String id) {
        try {
            Map<String, Object> user = gateway.call("resolve_actor", Map.of("id", Long.parseLong(id)), Map.of());
            if (!id.equals(String.valueOf(user.get("id")))) throw AiExceptions.unauthorized("账号不可用");
            var permissions = roles(user.get("permissions"));
            if (!permissions.contains("ai:use")) throw AiExceptions.unauthorized("账号无权使用 AI");
            return new UserIdentity(
                    id,
                    String.valueOf(user.get("username")),
                    roles(user.get("roles")),
                    permissions,
                    version(user.get("authVersion")));
        } catch (McpGateway.ToolFailure exception) {
            if (exception.status() == 404) throw AiExceptions.unauthorized("账号不可用");
            throw AiExceptions.upstream("身份校验服务不可用");
        } catch (AiExceptions.AiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw AiExceptions.upstream("身份校验服务不可用");
        }
    }

    private UserIdentity verify(String cookieHeader) {
        Map<String, Object> me;
        try {
            me = gateway.call("resolve_session", Map.of(), Map.of("Cookie", cookieHeader));
        } catch (McpGateway.ToolFailure exception) {
            if (exception.status() == 401) {
                throw AiExceptions.unauthorized("未登录或会话已过期");
            }
            throw AiExceptions.upstream("身份校验服务不可用");
        } catch (RuntimeException exception) {
            throw AiExceptions.upstream("身份校验服务不可用");
        }
        if (!Boolean.TRUE.equals(me.get("authenticated"))) {
            throw AiExceptions.unauthorized("未登录或会话已过期");
        }
        Object username = me.get("username");
        if (username == null || String.valueOf(username).isBlank()) {
            throw AiExceptions.unauthorized("身份信息不完整");
        }
        Object id = me.get("id");
        if (id == null) throw AiExceptions.unauthorized("身份信息不完整");
        return new UserIdentity(
                String.valueOf(id),
                String.valueOf(username),
                roles(me.get("roles")),
                roles(me.get("permissions")),
                version(me.get("authVersion")));
    }

    private static List<String> roles(Object raw) {
        List<String> roles = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String role = String.valueOf(item);
                roles.add(role.startsWith("ROLE_") ? role.substring(5) : role);
            }
        }
        return List.copyOf(roles);
    }

    private static long version(Object value) {
        if (!(value instanceof Number number)) throw AiExceptions.unauthorized("身份信息不完整");
        return number.longValue();
    }
}
