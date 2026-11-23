package top.yealike.pep.modelgateway.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.modelgateway.catalog.CatalogService;
import top.yealike.pep.modelgateway.catalog.domain.ModelProfile;
import top.yealike.pep.modelgateway.catalog.domain.Provider;
import top.yealike.pep.modelgateway.catalog.dto.ModelRequest;
import top.yealike.pep.modelgateway.catalog.dto.ProviderRequest;

/** 逻辑模型目录管理；上游密钥只写不读，列表接口不会回显任何密钥。 */
@RestController
@RequestMapping(path = "/api/model-gateway/v1/admin", produces = "application/json")
@Tag(name = "模型网关-模型目录", description = "供应商与逻辑模型的登记和查询；供应商密钥只写不读")
@SecurityRequirement(name = "serviceToken")
public class ModelAdminController {

    private final CatalogService catalog;

    public ModelAdminController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @Operation(operationId = "registerProvider", summary = "登记或更新模型供应商")
    @PostMapping("/providers")
    public Map<String, Object> provider(@RequestBody ProviderRequest request) {
        Provider provider =
                catalog.registerProvider(request.code(), request.name(), request.baseUrl(), request.apiKey());
        return describe(provider);
    }

    @Operation(operationId = "listProviders", summary = "列出模型供应商")
    @GetMapping("/providers")
    public List<Map<String, Object>> providers() {
        return catalog.providers().stream().map(ModelAdminController::describe).toList();
    }

    @Operation(operationId = "registerModel", summary = "登记或更新逻辑模型")
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

    @Operation(operationId = "listModels", summary = "列出逻辑模型")
    @GetMapping("/models")
    public List<Map<String, Object>> models() {
        return catalog.models().stream().map(ModelAdminController::describe).toList();
    }

    static Map<String, Object> describe(Provider provider) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", provider.id().toString());
        body.put("code", provider.code());
        body.put("name", provider.name());
        body.put("baseUrl", provider.baseUrl());
        body.put("state", provider.state());
        body.put("credentialConfigured", provider.credentialCipher() != null);
        return body;
    }

    static Map<String, Object> describe(ModelProfile profile) {
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
