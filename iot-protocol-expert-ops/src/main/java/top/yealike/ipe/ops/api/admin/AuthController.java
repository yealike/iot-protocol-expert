package top.yealike.ipe.ops.api.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPA 会话探测。
 *
 * <p>登录仍走 Spring Security 的 {@code POST /login}（表单参数），成功后由 JSESSIONID 维持会话。 这里只提供「我是谁」，避免前端把角色写死。
 */
@RestController
@RequestMapping("/api/ops/auth")
public class AuthController {

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication, HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (authentication == null || !authentication.isAuthenticated()) {
            result.put("authenticated", false);
            return result;
        }
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring(5))
                .toList();
        result.put("authenticated", true);
        result.put("username", authentication.getName());
        result.put("roles", roles);
        result.put(
                "sessionId",
                request.getSession(false) == null
                        ? null
                        : request.getSession(false).getId());
        return result;
    }
}
