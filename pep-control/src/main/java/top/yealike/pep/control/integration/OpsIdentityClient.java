package top.yealike.pep.control.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import top.yealike.pep.control.access.UserIdentity;
import top.yealike.pep.control.agents.config.PlatformProperties;
import top.yealike.pep.control.agents.registry.NacosRegistryService;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.control.support.JsonSupport;

/**
 * OPS 基础身份通道。
 *
 * <p>只调用 OPS 固定的内部身份接口，不再经过模型可配置的 MCP。OPS 仍为唯一身份来源。
 */
@Service
public class OpsIdentityClient {

    private final PlatformProperties properties;
    private final NacosRegistryService registry;
    private final RestClient http;

    public OpsIdentityClient(PlatformProperties properties, NacosRegistryService registry, RestClient.Builder builder) {
        this.properties = properties;
        this.registry = registry;
        this.http = builder.build();
    }

    public UserIdentity resolveCookie(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            throw AiExceptions.unauthorized("未登录或会话已过期");
        }
        Map<String, Object> body = post("/internal/ai/v1/identity/session", Map.of("cookie", cookieHeader));
        return identity(body);
    }

    public UserIdentity resolveActor(String actorId) {
        long id;
        try {
            id = Long.parseLong(actorId);
        } catch (NumberFormatException exception) {
            throw AiExceptions.unauthorized("账号不可用");
        }
        Map<String, Object> body = get("/internal/ai/v1/identity/actors/" + id);
        return identity(body);
    }

    private UserIdentity identity(Map<String, Object> body) {
        if (!Boolean.TRUE.equals(body.get("authenticated"))) {
            throw AiExceptions.unauthorized("未登录或会话已过期");
        }
        Object id = body.get("id");
        Object username = body.get("username");
        if (id == null || username == null || String.valueOf(username).isBlank()) {
            throw AiExceptions.unauthorized("身份信息不完整");
        }
        List<String> permissions = strings(body.get("permissions"));
        if (!permissions.contains("ai:use")) {
            throw AiExceptions.unauthorized("账号无权使用 AI");
        }
        return new UserIdentity(
                String.valueOf(id),
                String.valueOf(username),
                strings(body.get("roles")),
                permissions,
                number(body.get("authVersion")));
    }

    private Map<String, Object> post(String path, Object body) {
        try {
            String response = http.post()
                    .uri(baseUrl() + path)
                    .header("X-Ops-Service-Token", serviceToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(JsonSupport.write(body))
                    .retrieve()
                    .body(String.class);
            return JsonSupport.readMap(response);
        } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized exception) {
            throw AiExceptions.unauthorized("未登录或会话已过期");
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound exception) {
            throw AiExceptions.unauthorized("账号不可用");
        } catch (RuntimeException exception) {
            throw AiExceptions.upstream("身份校验服务不可用");
        }
    }

    private Map<String, Object> get(String path) {
        try {
            String response = http.get()
                    .uri(baseUrl() + path)
                    .header("X-Ops-Service-Token", serviceToken())
                    .retrieve()
                    .body(String.class);
            return JsonSupport.readMap(response);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound exception) {
            throw AiExceptions.unauthorized("账号不可用");
        } catch (RuntimeException exception) {
            throw AiExceptions.upstream("身份校验服务不可用");
        }
    }

    private String baseUrl() {
        String configured = properties.identity().baseUrl();
        if (configured != null && !configured.isBlank()) {
            return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
        }
        NacosRegistryService.InstanceView instance =
                registry.pickHealthy(properties.identity().serviceName());
        if (instance == null) {
            throw AiExceptions.upstream("OPS 身份服务不可用");
        }
        return "http://" + instance.ip() + ":" + instance.port();
    }

    private String serviceToken() {
        String token = properties.identity().serviceToken();
        if (token == null || token.isBlank()) {
            throw AiExceptions.upstream("未配置 OPS 服务令牌");
        }
        return token;
    }

    private static List<String> strings(Object raw) {
        List<String> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String value = String.valueOf(item);
                values.add(value.startsWith("ROLE_") ? value.substring(5) : value);
            }
        }
        return List.copyOf(values);
    }

    private static long number(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        throw AiExceptions.unauthorized("身份信息不完整");
    }
}
