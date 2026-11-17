package top.yealike.pep.modelgateway.catalog;

import io.swagger.v3.oas.annotations.Operation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 逻辑模型目录管理；上游密钥只写不读，列表接口不会回显任何密钥。 */
@RestController
@RequestMapping(path = "/api/model-gateway/v1/admin", produces = "application/json")
public class ModelAdminController {

    private final CatalogService catalog;

    public ModelAdminController(CatalogService catalog) {
        this.catalog = catalog;
    }

    public record ProviderRequest(String code, String name, String baseUrl, String apiKey) {}

    public record ModelRequest(
            String key,
            String displayName,
            String providerCode,
            String upstreamModel,
            Integer contextWindow,
            Integer maxOutputTokens,
            String countingMode) {}

    @Operation(summary = "登记模型供应商", description = "密钥加密后保存，只用于模型网关访问上游")
    @PostMapping("/providers")
    public Map<String, Object> provider(@RequestBody ProviderRequest request) {
        CatalogRecords.Provider provider =
                catalog.registerProvider(request.code(), request.name(), request.baseUrl(), request.apiKey());
        return describe(provider);
    }

    @Operation(summary = "列出模型供应商", description = "只返回地址与状态，不返回密钥")
    @GetMapping("/providers")
    public List<Map<String, Object>> providers() {
        return catalog.providers().stream().map(ModelAdminController::describe).toList();
    }

    @Operation(summary = "登记逻辑模型", description = "Agent 只引用逻辑模型 key，不接触上游模型名和密钥")
    @PostMapping("/models")
    public Map<String, Object> model(@RequestBody ModelRequest request) {
        return describe(catalog.registerModel(
                request.key(),
                request.displayName(),
                request.providerCode(),
                request.upstreamModel(),
                request.contextWindow(),
                request.maxOutputTokens() == null ? 2048 : request.maxOutputTokens(),
                request.countingMode()));
    }

    @Operation(summary = "列出逻辑模型")
    @GetMapping("/models")
    public List<Map<String, Object>> models() {
        return catalog.models().stream().map(ModelAdminController::describe).toList();
    }

    static Map<String, Object> describe(CatalogRecords.Provider provider) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", provider.id().toString());
        body.put("code", provider.code());
        body.put("name", provider.name());
        body.put("baseUrl", provider.baseUrl());
        body.put("state", provider.state());
        body.put("credentialConfigured", provider.credentialCipher() != null);
        return body;
    }

    static Map<String, Object> describe(CatalogRecords.ModelProfile profile) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", profile.id().toString());
        body.put("key", profile.key());
        body.put("displayName", profile.displayName());
        body.put("providerId", profile.providerId().toString());
        body.put("upstreamModel", profile.upstreamModel());
        body.put("contextWindow", profile.contextWindow());
        body.put("maxOutputTokens", profile.maxOutputTokens());
        body.put("countingMode", profile.countingMode());
        body.put("state", profile.state());
        return body;
    }
}
