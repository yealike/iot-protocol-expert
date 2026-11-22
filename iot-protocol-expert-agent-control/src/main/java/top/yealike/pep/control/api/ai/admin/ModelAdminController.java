package top.yealike.pep.control.api.ai.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.control.access.CurrentUser;
import top.yealike.pep.control.agents.credential.CredentialService;
import top.yealike.pep.control.agents.model.ModelProfileService;
import top.yealike.pep.control.api.ai.admin.dto.CredentialCreate;
import top.yealike.pep.control.api.ai.admin.dto.CredentialRotate;
import top.yealike.pep.control.api.ai.admin.dto.ModelCreate;
import top.yealike.pep.control.api.ai.admin.dto.ModelUpdate;
import top.yealike.pep.runtime.contract.UserIdentity;

/** 模型配置与供应商凭据管理。 */
@RestController
@RequestMapping("/api/ai/v1")
@PreAuthorize("hasAuthority('ai:admin')")
@Tag(name = "AI 模型与凭据", description = "模型 profile 与只写不读的供应商凭据")
public class ModelAdminController {

    private final ModelProfileService models;
    private final CredentialService credentials;

    public ModelAdminController(ModelProfileService models, CredentialService credentials) {
        this.models = models;
        this.credentials = credentials;
    }

    @GetMapping("/models")
    @Operation(summary = "查询模型配置")
    public Object listModels() {
        return models.list();
    }

    @PostMapping("/models")
    @Operation(summary = "创建模型配置")
    public Object createModel(@RequestBody ModelCreate request) {
        UserIdentity actor = CurrentUser.require();
        UUID id = models.create(
                actor,
                request.key(),
                request.displayName(),
                request.description(),
                request.baseUrl(),
                request.modelName(),
                request.credentialId(),
                request.options());
        return models.require(id);
    }

    @PutMapping("/models/{id}")
    @Operation(summary = "更新模型配置")
    public Object updateModel(
            @Parameter(description = "模型配置 ID") @PathVariable UUID id, @RequestBody ModelUpdate request) {
        return models.update(
                id,
                request.expectedRevision(),
                request.displayName(),
                request.description(),
                request.baseUrl(),
                request.modelName(),
                request.credentialId(),
                request.options(),
                request.enabled());
    }

    @PostMapping("/models/{id}/test")
    @Operation(summary = "测试模型连接")
    public Object testModel(@Parameter(description = "模型配置 ID") @PathVariable UUID id) {
        return models.test(id);
    }

    @GetMapping("/credentials")
    @Operation(summary = "查询凭据列表", description = "仅返回凭据摘要，不返回密钥明文")
    public Object listCredentials() {
        return credentials.list().stream().map(credentials::summary).toList();
    }

    @PostMapping("/credentials")
    @Operation(summary = "创建模型凭据")
    public Object createCredential(@RequestBody CredentialCreate request) {
        UserIdentity actor = CurrentUser.require();
        return credentials.summary(credentials.create(actor, request.key(), request.displayName(), request.secret()));
    }

    @PutMapping("/credentials/{id}")
    @Operation(summary = "轮换模型凭据密钥")
    public Object rotateCredential(
            @Parameter(description = "凭据 ID") @PathVariable UUID id, @RequestBody CredentialRotate request) {
        UserIdentity actor = CurrentUser.require();
        return credentials.summary(credentials.rotate(actor, id, request.secret()));
    }
}
