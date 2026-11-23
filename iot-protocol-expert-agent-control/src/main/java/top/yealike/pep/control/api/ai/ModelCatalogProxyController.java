package top.yealike.pep.control.api.ai;

import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.control.access.CurrentUser;
import top.yealike.pep.control.agents.model.ModelCatalogGatewayClient;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.model.contract.gateway.GatewayModel;
import top.yealike.pep.model.contract.gateway.GatewayModelRequest;
import top.yealike.pep.model.contract.gateway.GatewayProvider;
import top.yealike.pep.model.contract.gateway.GatewayProviderRequest;

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
    public List<GatewayProvider> providers() {
        CurrentUser.require();
        return gateway.providers();
    }

    @Operation(summary = "登记模型供应商", description = "上游密钥只写不读")
    @PostMapping("/providers")
    public GatewayProvider registerProvider(@RequestBody GatewayProviderRequest request) {
        CurrentUser.require();
        requireText(request.code(), "供应商编码");
        requireText(request.name(), "供应商名称");
        requireText(request.baseUrl(), "Base URL");
        requireText(request.apiKey(), "API Key");
        return gateway.registerProvider(new GatewayProviderRequest(
                request.code().trim(), request.name().trim(), request.baseUrl().trim(), request.apiKey()));
    }

    @Operation(summary = "查询逻辑模型")
    @GetMapping("/models")
    public List<GatewayModel> models() {
        CurrentUser.require();
        return gateway.models();
    }

    @Operation(summary = "登记逻辑模型")
    @PostMapping("/models")
    public GatewayModel registerModel(@RequestBody GatewayModelRequest request) {
        CurrentUser.require();
        requireText(request.key(), "逻辑模型 key");
        requireText(request.displayName(), "显示名称");
        requireText(request.providerCode(), "供应商编码");
        requireText(request.upstreamModel(), "上游模型名");
        if (request.contextWindow() != null) {
            if (request.contextWindow() <= 0) throw AiExceptions.badRequest("上下文窗口必须大于 0");
        }
        if (request.maxOutputTokens() != null) {
            if (request.maxOutputTokens() <= 0) throw AiExceptions.badRequest("最大输出 Token 必须大于 0");
        }
        return gateway.registerModel(new GatewayModelRequest(
                request.key().trim(),
                request.displayName().trim(),
                request.providerCode().trim(),
                request.upstreamModel().trim(),
                request.contextWindow(),
                request.maxOutputTokens(),
                request.countingMode() == null || request.countingMode().isBlank()
                        ? null
                        : request.countingMode().trim()));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw AiExceptions.badRequest(field + "不能为空");
        }
    }
}
