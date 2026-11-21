package top.yealike.pep.ops.api.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.yealike.pep.ops.access.IdentityService;

/**
 * AI Control 专用的基础身份通道。只允许持有服务令牌的内部调用，不进入模型工具目录。
 */
@RestController
@RequestMapping(path = "/internal/ai/v1/identity", produces = "application/json")
public class OpsIdentityController {

    private final OpsSessionLookup sessions;
    private final IdentityService identities;

    public OpsIdentityController(OpsSessionLookup sessions, IdentityService identities) {
        this.sessions = sessions;
        this.identities = identities;
    }

    @PostMapping(path = "/session", consumes = "application/json")
    public Map<String, Object> session(@RequestBody Map<String, Object> body) {
        String cookie = body == null ? null : String.valueOf(body.getOrDefault("cookie", ""));
        String sessionId = sessionId(cookie);
        if (sessionId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或会话已过期");
        }
        Map<String, Object> resolved = sessions.resolve(sessionId);
        if (resolved == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或会话已过期");
        }
        return enrich(resolved);
    }

    @GetMapping("/actors/{id}")
    public Map<String, Object> actor(@PathVariable long id) {
        IdentityService.Identity identity = identities
                .byId(id)
                .filter(IdentityService.Identity::enabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不可用"));
        return describe(identity);
    }

    private Map<String, Object> enrich(Map<String, Object> resolved) {
        Object id = resolved.get("id");
        if (!(id instanceof Number number)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "身份信息不完整");
        }
        IdentityService.Identity identity = identities
                .byId(number.longValue())
                .filter(IdentityService.Identity::enabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号不可用"));
        return describe(identity);
    }

    private Map<String, Object> describe(IdentityService.Identity identity) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("authenticated", true);
        data.put("id", identity.id());
        data.put("username", identity.username());
        data.put("roles", identity.roles());
        data.put("permissions", identity.permissions());
        data.put("authVersion", identity.authVersion());
        return data;
    }

    private static String sessionId(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return null;
        }
        for (String part : cookieHeader.split(";")) {
            String cookie = part.strip();
            if (cookie.startsWith("JSESSIONID=")) {
                String value = cookie.substring("JSESSIONID=".length());
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }
}
