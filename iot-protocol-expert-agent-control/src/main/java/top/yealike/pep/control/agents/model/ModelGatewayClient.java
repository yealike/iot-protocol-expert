package top.yealike.pep.control.agents.model;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import top.yealike.pep.control.config.ModelGatewayProperties;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.control.support.JsonSupport;
import top.yealike.pep.model.contract.ModelCallHeaders;
import top.yealike.pep.model.contract.gateway.QuotaGrantRequest;
import top.yealike.pep.model.contract.gateway.QuotaGrantResult;
import top.yealike.pep.model.contract.gateway.QuotaSnapshot;
import top.yealike.pep.model.contract.gateway.ReconcileReleaseResult;
import top.yealike.pep.model.contract.gateway.UsageList;
import top.yealike.pep.observability.api.ApiResponse;

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
                    .header(ModelCallHeaders.LOGICAL_CALL_ID, UUID.randomUUID().toString())
                    .header(ModelCallHeaders.ATTEMPT_NO, "1")
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

    public QuotaSnapshot quota(String scopeKind, String subjectId) {
        String path = UriComponentsBuilder.fromPath("/api/model-gateway/v1/me/quota")
                .queryParam("scopeKind", scopeKind)
                .queryParam("subjectId", subjectId)
                .build()
                .toUriString();
        return get(path, QuotaSnapshot.class);
    }

    public UsageList usage(String scopeKind, String subjectId, int limit, String model, String projectId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/api/model-gateway/v1/me/usage")
                .queryParam("scopeKind", scopeKind)
                .queryParam("subjectId", subjectId)
                .queryParam("limit", limit);
        if (model != null && !model.isBlank()) {
            builder.queryParam("model", model);
        }
        if (projectId != null && !projectId.isBlank()) {
            builder.queryParam("projectId", projectId);
        }
        return get(builder.build().toUriString(), UsageList.class);
    }

    public QuotaGrantResult grant(QuotaGrantRequest request, String idempotencyKey) {
        try {
            String response = http.post()
                    .uri(baseUrl() + "/api/model-gateway/v1/admin/quota-grants")
                    .header("X-PEP-Service-Token", serviceToken())
                    .header("Idempotency-Key", idempotencyKey)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            return unwrap(response, QuotaGrantResult.class);
        } catch (RuntimeException exception) {
            throw AiExceptions.unavailable("模型网关额度管理暂不可用");
        }
    }

    public ReconcileReleaseResult reconcileRelease(UUID reservationId) {
        return post(
                "/api/model-gateway/v1/admin/calls/" + reservationId + "/reconcile-release",
                null,
                ReconcileReleaseResult.class);
    }

    private <T> T get(String path, Class<T> type) {
        String url = baseUrl() + path;
        String token = serviceToken();
        try {
            String response = http.get()
                    .uri(url)
                    .header("X-PEP-Service-Token", token)
                    .retrieve()
                    .body(String.class);
            return unwrap(response, type);
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

    private <T> T post(String path, Object body, Class<T> type) {
        try {
            String response = http.post()
                    .uri(baseUrl() + path)
                    .header("X-PEP-Service-Token", serviceToken())
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return unwrap(response, type);
        } catch (org.springframework.web.client.RestClientResponseException exception) {
            log.warn("模型网关管理接口失败，HTTP 状态码：{}", exception.getStatusCode().value());
            throw AiExceptions.unavailable("模型网关管理接口暂不可用");
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

    private static <T> T unwrap(String text, Class<T> type) {
        if (text == null || text.isBlank()) {
            return null;
        }
        var root = JsonSupport.mapper().readTree(text);
        if (root != null && root.has("code") && root.has("msg") && root.has("data")) {
            String code = root.path("code").asText();
            if (!ApiResponse.SUCCESS_CODE.equals(code)) {
                throw AiExceptions.unavailable("模型网关返回错误：" + root.path("msg").asText());
            }
            var data = root.get("data");
            return data == null || data.isNull() ? null : JsonSupport.read(JsonSupport.write(data), type);
        }
        return JsonSupport.read(text, type);
    }

    private String serviceToken() {
        String value = properties.getServiceToken();
        if (value == null || value.isBlank()) {
            throw AiExceptions.unavailable("未配置模型网关服务令牌");
        }
        return value;
    }
}
