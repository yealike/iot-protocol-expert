package top.yealike.pep.modelgateway.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.model.contract.gateway.GatewayModel;
import top.yealike.pep.model.contract.gateway.GatewayModelRequest;
import top.yealike.pep.model.contract.gateway.GatewayProvider;
import top.yealike.pep.model.contract.gateway.GatewayProviderRequest;
import top.yealike.pep.modelgateway.catalog.CatalogService;
import top.yealike.pep.modelgateway.catalog.domain.ModelProfile;
import top.yealike.pep.modelgateway.catalog.domain.Provider;

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
    public GatewayProvider provider(@RequestBody GatewayProviderRequest request) {
        Provider provider =
                catalog.registerProvider(request.code(), request.name(), request.baseUrl(), request.apiKey());
        return describe(provider);
    }

    @Operation(operationId = "listProviders", summary = "列出模型供应商")
    @GetMapping("/providers")
    public List<GatewayProvider> providers() {
        return catalog.providers().stream().map(ModelAdminController::describe).toList();
    }

    @Operation(operationId = "registerModel", summary = "登记或更新逻辑模型")
    @PostMapping("/models")
    public GatewayModel model(@RequestBody GatewayModelRequest request) {
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
    public List<GatewayModel> models() {
        return catalog.models().stream().map(ModelAdminController::describe).toList();
    }

    static GatewayProvider describe(Provider provider) {
        return new GatewayProvider(
                provider.id(),
                provider.code(),
                provider.name(),
                provider.baseUrl(),
                provider.state(),
                provider.credentialCipher() != null);
    }

    static GatewayModel describe(ModelProfile profile) {
        return new GatewayModel(
                profile.id(),
                profile.key(),
                profile.displayName(),
                profile.providerId(),
                profile.upstreamModel(),
                profile.contextWindow(),
                profile.maxOutputTokens(),
                profile.countingMode(),
                profile.state());
    }
}
