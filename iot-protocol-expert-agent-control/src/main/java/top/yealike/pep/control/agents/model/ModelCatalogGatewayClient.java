package top.yealike.pep.control.agents.model;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import top.yealike.pep.control.config.ModelGatewayProperties;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.control.support.JsonSupport;

@Service
public class ModelCatalogGatewayClient {

    private final ModelGatewayProperties properties;
    private final RestClient http;

    public ModelCatalogGatewayClient(ModelGatewayProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.http = builder.build();
    }

    public List<Map<String, Object>> providers() {
        return getList("/api/model-gateway/v1/admin/providers");
    }

    public List<Map<String, Object>> models() {
        return getList("/api/model-gateway/v1/admin/models");
    }

    public Map<String, Object> registerProvider(Map<String, Object> request) {
        return post("/api/model-gateway/v1/admin/providers", request);
    }

    public Map<String, Object> registerModel(Map<String, Object> request) {
        return post("/api/model-gateway/v1/admin/models", request);
    }

    private List<Map<String, Object>> getList(String path) {
        try {
            String response = http.get()
                    .uri(baseUrl() + path)
                    .header("X-PEP-Service-Token", serviceToken())
                    .retrieve()
                    .body(String.class);
            return JsonSupport.mapper().readValue(response, new TypeReference<List<Map<String, Object>>>() {});
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

    private Map<String, Object> post(String path, Map<String, Object> request) {
        try {
            String response = http.post()
                    .uri(baseUrl() + path)
                    .header("X-PEP-Service-Token", serviceToken())
                    .body(request)
                    .retrieve()
                    .body(String.class);
            return JsonSupport.readMap(response);
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

    private String serviceToken() {
        String value = properties.getServiceToken();
        if (value == null || value.isBlank()) {
            throw AiExceptions.unavailable("未配置模型网关服务令牌");
        }
        return value;
    }
}
