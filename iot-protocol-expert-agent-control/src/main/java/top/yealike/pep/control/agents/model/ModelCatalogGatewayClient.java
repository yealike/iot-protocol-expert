package top.yealike.pep.control.agents.model;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import top.yealike.pep.control.config.ModelGatewayProperties;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.control.support.JsonSupport;
import top.yealike.pep.model.contract.gateway.GatewayModel;
import top.yealike.pep.model.contract.gateway.GatewayModelRequest;
import top.yealike.pep.model.contract.gateway.GatewayProvider;
import top.yealike.pep.model.contract.gateway.GatewayProviderRequest;
import top.yealike.pep.observability.api.ApiResponse;

@Service
public class ModelCatalogGatewayClient {

    private final ModelGatewayProperties properties;
    private final RestClient http;

    public ModelCatalogGatewayClient(ModelGatewayProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.http = builder.build();
    }

    public List<GatewayProvider> providers() {
        return getList("/api/model-gateway/v1/admin/providers", GatewayProvider.class);
    }

    public List<GatewayModel> models() {
        return getList("/api/model-gateway/v1/admin/models", GatewayModel.class);
    }

    public GatewayProvider registerProvider(GatewayProviderRequest request) {
        return post("/api/model-gateway/v1/admin/providers", request, GatewayProvider.class);
    }

    public GatewayModel registerModel(GatewayModelRequest request) {
        return post("/api/model-gateway/v1/admin/models", request, GatewayModel.class);
    }

    private <T> List<T> getList(String path, Class<T> type) {
        try {
            String response = http.get()
                    .uri(baseUrl() + path)
                    .header("X-PEP-Service-Token", serviceToken())
                    .retrieve()
                    .body(String.class);
            return unwrapList(response, type);
        } catch (org.springframework.web.client.RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401
                    || exception.getStatusCode().value() == 403) {
                throw AiExceptions.unavailable("模型网关服务身份校验失败，请检查控制服务与网关的共享令牌配置");
            }
            throw AiExceptions.unavailable("模型网关目录查询暂不可用");
        } catch (RuntimeException exception) {
            throw AiExceptions.unavailable("模型网关目录查询暂不可用");
        }
    }

    private <T> T post(String path, Object request, Class<T> type) {
        try {
            String response = http.post()
                    .uri(baseUrl() + path)
                    .header("X-PEP-Service-Token", serviceToken())
                    .body(request)
                    .retrieve()
                    .body(String.class);
            return unwrap(response, type);
        } catch (org.springframework.web.client.RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401
                    || exception.getStatusCode().value() == 403) {
                throw AiExceptions.unavailable("模型网关服务身份校验失败，请检查控制服务与网关的共享令牌配置");
            }
            if (exception.getStatusCode().is4xxClientError()) {
                throw AiExceptions.badRequest("模型网关拒绝了登记请求，请检查编码、地址和模型参数");
            }
            throw AiExceptions.unavailable("模型网关目录登记暂不可用");
        } catch (RuntimeException exception) {
            throw AiExceptions.unavailable("模型网关目录登记暂不可用");
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
            requireSuccess(root);
            var data = root.get("data");
            return data == null || data.isNull() ? null : JsonSupport.read(JsonSupport.write(data), type);
        }
        return JsonSupport.read(text, type);
    }

    private static <T> List<T> unwrapList(String text, Class<T> type) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        var root = JsonSupport.mapper().readTree(text);
        if (root != null && root.has("code") && root.has("msg") && root.has("data")) {
            requireSuccess(root);
            var data = root.get("data");
            return data == null || data.isNull() ? List.of() : JsonSupport.readList(JsonSupport.write(data), type);
        }
        return JsonSupport.readList(text, type);
    }

    private static void requireSuccess(tools.jackson.databind.JsonNode root) {
        if (!ApiResponse.SUCCESS_CODE.equals(root.path("code").asText())) {
            throw AiExceptions.unavailable("模型网关返回错误：" + root.path("msg").asText());
        }
    }

    private String serviceToken() {
        String value = properties.getServiceToken();
        if (value == null || value.isBlank()) {
            throw AiExceptions.unavailable("未配置模型网关服务令牌");
        }
        return value;
    }
}
