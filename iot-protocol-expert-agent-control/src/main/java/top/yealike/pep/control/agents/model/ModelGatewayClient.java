package top.yealike.pep.control.agents.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import top.yealike.pep.control.config.ModelGatewayProperties;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.control.support.JsonSupport;

/** 控制服务对模型网关的调用；浏览器和 Runtime 都不直接访问网关管理面。 */
@Service
public class ModelGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(ModelGatewayClient.class);

    private final ModelGatewayProperties properties;
    private final RestClient http;

    public ModelGatewayClient(ModelGatewayProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.http = builder.build();
    }

    public Map<String, Object> chat(String runToken, Map<String, Object> body) {
        try {
            String response = http.post()
                    .uri(baseUrl() + "/v1/chat/completions")
                    .header("Authorization", "Bearer " + runToken)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return JsonSupport.readMap(response);
        } catch (org.springframework.web.client.HttpClientErrorException exception) {
            throw new AiExceptions.AiException(
                    exception.getStatusCode().value(),
                    "MODEL_GATEWAY_REJECTED",
                    "模型网关拒绝调用：" + exception.getStatusCode().value());
        } catch (RuntimeException exception) {
            throw AiExceptions.unavailable("模型网关暂不可用");
        }
    }

    public Map<String, Object> quota(String scopeKind, String subjectId) {
        return get("/api/model-gateway/v1/me/quota?scopeKind=" + scopeKind + "&subjectId=" + subjectId);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> usage(
            String scopeKind, String subjectId, int limit, String model, String projectId) {
        StringBuilder path = new StringBuilder("/api/model-gateway/v1/me/usage?scopeKind=")
                .append(scopeKind)
                .append("&subjectId=")
                .append(subjectId)
                .append("&limit=")
                .append(limit);
        if (model != null && !model.isBlank()) {
            path.append("&model=").append(model);
        }
        if (projectId != null && !projectId.isBlank()) {
            path.append("&projectId=").append(projectId);
        }
        Object payload = get(path.toString()).get("items");
        return payload instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    public Map<String, Object> grant(Map<String, Object> request, String idempotencyKey) {
        try {
            String response = http.post()
                    .uri(baseUrl() + "/api/model-gateway/v1/admin/quota-grants")
                    .header("X-PEP-Service-Token", serviceToken())
                    .header("Idempotency-Key", idempotencyKey)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            return JsonSupport.readMap(response);
        } catch (RuntimeException exception) {
            throw AiExceptions.unavailable("模型网关额度管理暂不可用");
        }
    }

    public Map<String, Object> registerProvider(Map<String, Object> request) {
        return post("/api/model-gateway/v1/admin/providers", request);
    }

    public Map<String, Object> registerModel(Map<String, Object> request) {
        return post("/api/model-gateway/v1/admin/models", request);
    }

    public Map<String, Object> setAccountState(UUID accountId, String state, String actor, String reason) {
        String path = org.springframework.web.util.UriComponentsBuilder.fromPath(
                        "/api/model-gateway/v1/admin/quota-accounts/{id}/state")
                .queryParam("state", state)
                .queryParam("actorId", actor)
                .queryParam("reason", reason == null ? "" : reason)
                .buildAndExpand(accountId)
                .encode()
                .toUriString();
        return post(path, Map.of());
    }

    public Map<String, Object> adjustUsage(Map<String, Object> request, String idempotencyKey) {
        try {
            String response = http.post()
                    .uri(baseUrl() + "/api/model-gateway/v1/admin/usage-adjustments")
                    .header("X-PEP-Service-Token", serviceToken())
                    .header("Idempotency-Key", idempotencyKey)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            return JsonSupport.readMap(response);
        } catch (RuntimeException exception) {
            throw AiExceptions.unavailable("模型网关用量调整暂不可用");
        }
    }

    public Map<String, Object> reconcileRelease(UUID reservationId) {
        return post("/api/model-gateway/v1/admin/calls/" + reservationId + "/reconcile-release", Map.of());
    }

    private Map<String, Object> get(String path) {
        String url = baseUrl() + path;
        String token = serviceToken();
        try {
            String response = http.get()
                    .uri(url)
                    .header("X-PEP-Service-Token", token)
                    .retrieve()
                    .body(String.class);
            return JsonSupport.readMap(response);
        } catch (org.springframework.web.client.RestClientResponseException exception) {
            log.error("模型网关查询失败，HTTP 状态码：{}", exception.getStatusCode().value(), exception);
            if (exception.getStatusCode().value() == 401
                    || exception.getStatusCode().value() == 403) {
                throw AiExceptions.unavailable("模型网关服务身份校验失败，请检查控制服务与网关的共享令牌配置");
            }
            throw AiExceptions.unavailable("模型网关查询暂不可用");
        } catch (RuntimeException exception) {
            log.error("模型网关查询失败", exception);
            throw AiExceptions.unavailable("模型网关查询暂不可用");
        }
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        try {
            String response = http.post()
                    .uri(baseUrl() + path)
                    .header("X-PEP-Service-Token", serviceToken())
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return JsonSupport.readMap(response);
        } catch (org.springframework.web.client.RestClientResponseException exception) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("status", exception.getStatusCode().value());
            detail.put("body", exception.getResponseBodyAsString());
            throw AiExceptions.unavailable("模型网关管理接口暂不可用：" + detail);
        } catch (RuntimeException exception) {
            throw AiExceptions.unavailable("模型网关管理接口暂不可用");
        }
    }

    private String baseUrl() {
        String value = properties.getBaseUrl();
        if (value == null || value.isBlank()) {
            throw AiExceptions.unavailable("未配置模型网关地址");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String serviceToken() {
        String value = properties.getServiceToken();
        if (value == null || value.isBlank()) {
            throw AiExceptions.unavailable("未配置模型网关服务令牌");
        }
        return value;
    }
}
