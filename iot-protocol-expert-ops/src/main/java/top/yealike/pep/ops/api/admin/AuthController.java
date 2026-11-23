package top.yealike.pep.ops.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.ops.access.domain.Identity;

/**
 * SPA 会话探测。
 *
 * <p>登录仍走 Spring Security 的 {@code POST /login}（表单参数），成功后由 JSESSIONID 维持会话。 这里只提供「我是谁」，避免前端把角色写死。
 */
@RestController
@RequestMapping(path = "/api/ops/auth", produces = "application/json")
@Tag(name = "登录会话", description = "查询当前登录用户与权限")
public class AuthController {
    private final top.yealike.pep.ops.access.service.IdentityService identities;

    public AuthController(top.yealike.pep.ops.access.service.IdentityService identities) {
        this.identities = identities;
    }

    @GetMapping("/me")
    @Operation(operationId = "getCurrentIdentity", summary = "查询当前登录身份")
    @SecurityRequirement(name = "sessionCookie")
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
        var identity = identities
                .byUsername(authentication.getName())
                .filter(Identity::enabled)
                .orElseThrow();
        result.put("authenticated", true);
        result.put("id", String.valueOf(identity.id()));
        result.put("permissions", identity.permissions());
        result.put("authVersion", identity.authVersion());
        result.put("username", authentication.getName());
        result.put("roles", roles);
        return result;
    }
}
