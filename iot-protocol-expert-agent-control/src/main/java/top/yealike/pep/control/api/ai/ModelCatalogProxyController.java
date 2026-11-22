package top.yealike.pep.control.api.ai;

import io.swagger.v3.oas.annotations.Operation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.control.access.CurrentUser;
import top.yealike.pep.control.agents.model.ModelCatalogGatewayClient;
import top.yealike.pep.control.api.ai.dto.ModelCreate;
import top.yealike.pep.control.api.ai.dto.ProviderCreate;
import top.yealike.pep.control.support.AiExceptions;

@RestController
@RequestMapping(path = "/api/ai/v1/model-catalog", produces = "application/json")
@PreAuthorize("hasAuthority('ai:admin')")
public class ModelCatalogProxyController {

    private final ModelCatalogGatewayClient gateway;

    public ModelCatalogProxyController(ModelCatalogGatewayClient gateway) {
        this.gateway = gateway;
    }

    @Operation(summary = "查询模型供应商")
    @GetMapping("/providers")
    public List<Map<String, Object>> providers() {
        CurrentUser.require();
        return gateway.providers();
    }

    @Operation(summary = "登记模型供应商", description = "上游密钥只写不读")
    @PostMapping("/providers")
    public Map<String, Object> registerProvider(@RequestBody ProviderCreate request) {
        CurrentUser.require();
        requireText(request.code(), "供应商编码");
        requireText(request.name(), "供应商名称");
        requireText(request.baseUrl(), "Base URL");
        requireText(request.apiKey(), "API Key");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", request.code().trim());
        body.put("name", request.name().trim());
        body.put("baseUrl", request.baseUrl().trim());
        body.put("apiKey", request.apiKey());
        return gateway.registerProvider(body);
    }

    @Operation(summary = "查询逻辑模型")
    @GetMapping("/models")
    public List<Map<String, Object>> models() {
        CurrentUser.require();
        return gateway.models();
    }

    @Operation(summary = "登记逻辑模型")
    @PostMapping("/models")
    public Map<String, Object> registerModel(@RequestBody ModelCreate request) {
        CurrentUser.require();
        requireText(request.key(), "逻辑模型 key");
        requireText(request.displayName(), "显示名称");
        requireText(request.providerCode(), "供应商编码");
        requireText(request.upstreamModel(), "上游模型名");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", request.key().trim());
        body.put("displayName", request.displayName().trim());
        body.put("providerCode", request.providerCode().trim());
        body.put("upstreamModel", request.upstreamModel().trim());
        if (request.contextWindow() != null) {
            if (request.contextWindow() <= 0) throw AiExceptions.badRequest("上下文窗口必须大于 0");
            body.put("contextWindow", request.contextWindow());
        }
        if (request.maxOutputTokens() != null) {
            if (request.maxOutputTokens() <= 0) throw AiExceptions.badRequest("最大输出 Token 必须大于 0");
            body.put("maxOutputTokens", request.maxOutputTokens());
        }
        if (request.countingMode() != null && !request.countingMode().isBlank()) {
            body.put("countingMode", request.countingMode().trim());
        }
        return gateway.registerModel(body);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw AiExceptions.badRequest(field + "不能为空");
        }
    }
}
