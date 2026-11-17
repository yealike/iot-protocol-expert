package top.yealike.pep.control.api.ai;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.control.access.CurrentUser;
import top.yealike.pep.control.agents.AgentService;
import top.yealike.pep.control.agents.credential.CredentialService;
import top.yealike.pep.control.agents.domain.PlatformRecords.AgentComponent;
import top.yealike.pep.control.agents.domain.PlatformRecords.AgentRelease;
import top.yealike.pep.control.agents.domain.PlatformRecords.McpRelease;
import top.yealike.pep.control.agents.domain.PlatformRecords.Publication;
import top.yealike.pep.control.agents.domain.PlatformRecords.RegistryProjection;
import top.yealike.pep.control.agents.domain.PlatformRecords.SkillDraftFile;
import top.yealike.pep.control.agents.domain.PlatformRecords.SkillRelease;
import top.yealike.pep.control.agents.mcp.McpService;
import top.yealike.pep.control.agents.model.ModelProfileService;
import top.yealike.pep.control.agents.registry.NacosRegistryService;
import top.yealike.pep.control.agents.skill.SkillService;
import top.yealike.pep.control.agents.store.AgentStore.McpBindingInput;
import top.yealike.pep.control.agents.store.AgentStore.SkillBindingInput;
import top.yealike.pep.control.agents.store.PublicationStore;
import top.yealike.pep.control.agents.store.RegistryStore;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.runtime.contract.ComponentNames;
import top.yealike.pep.runtime.contract.ComponentType;
import top.yealike.pep.runtime.contract.UserIdentity;

/** 浏览器管理 API：模型、凭据、MCP、Skill、Agent 与发布/注册投影。只对 AI 管理员开放。 */
@RestController
@RequestMapping("/api/ai/v1")
@PreAuthorize("hasAuthority('ai:admin')")
@Tag(name = "AI 能力管理", description = "管理模型凭据、MCP、Skill、Agent 及其发布")
public class PlatformAdminController {

    private final ModelProfileService models;
    private final CredentialService credentials;
    private final McpService mcps;
    private final SkillService skills;
    private final AgentService agents;
    private final PublicationStore publications;
    private final RegistryStore registryStore;
    private final NacosRegistryService registry;

    public PlatformAdminController(
            ModelProfileService models,
            CredentialService credentials,
            McpService mcps,
            SkillService skills,
            AgentService agents,
            PublicationStore publications,
            RegistryStore registryStore,
            NacosRegistryService registry) {
        this.models = models;
        this.credentials = credentials;
        this.mcps = mcps;
        this.skills = skills;
        this.agents = agents;
        this.publications = publications;
        this.registryStore = registryStore;
        this.registry = registry;
    }

    // ------------------------------------------------------------------ 模型与凭据

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

    // ------------------------------------------------------------------ MCP

    @GetMapping("/mcps")
    @Operation(summary = "查询 MCP 服务")
    public Object listMcps() {
        return mcps.list();
    }

    @PostMapping("/mcps")
    @Operation(summary = "创建 MCP 草稿")
    public Object createMcp(@RequestBody McpCreate request) {
        UserIdentity actor = CurrentUser.require();
        UUID id = mcps.create(
                actor,
                request.key(),
                request.displayName(),
                request.description(),
                request.origin(),
                request.nativeRegistryRef(),
                request.draft());
        return mcps.require(id);
    }

    @PutMapping("/mcps/{id}")
    public Object updateMcp(@PathVariable UUID id, @RequestBody McpUpdate request) {
        return mcps.update(
                id,
                request.expectedRevision(),
                request.displayName(),
                request.description(),
                request.nativeRegistryRef(),
                request.draft(),
                request.enabled());
    }

    @DeleteMapping("/mcps/{id}")
    public void deleteMcp(@PathVariable UUID id) {
        mcps.delete(id);
    }

    @PostMapping("/mcps/{id}/test")
    @Operation(summary = "测试 MCP 连通性并读取工具目录")
    public Object testMcp(@Parameter(description = "MCP 组件 ID") @PathVariable UUID id) {
        return mcps.test(id);
    }

    @GetMapping("/mcps/{id}/tools")
    public Object mcpTools(@PathVariable UUID id) {
        return mcps.latestTools(id);
    }

    @GetMapping("/mcps/{id}/tool-policies")
    public Object mcpToolPolicies(@PathVariable UUID id) {
        return mcps.toolPolicies(id);
    }

    @PutMapping("/mcps/{id}/tool-policies")
    public Object reviewMcpTool(@PathVariable UUID id, @RequestBody McpToolPolicyUpdate request) {
        UserIdentity actor = CurrentUser.require();
        return mcps.reviewTool(actor, id, request.toolName(), request.executionCategory());
    }

    @GetMapping("/mcps/{id}/releases")
    public List<McpRelease> mcpReleases(@PathVariable UUID id) {
        return mcps.releases(id);
    }

    @PostMapping("/mcps/{id}/publish")
    @Operation(summary = "发布 MCP 版本", description = "冻结工具目录并异步注册组件版本")
    public ResponseEntity<McpService.PublishResult> publishMcp(@PathVariable UUID id, @RequestBody McpPublish request) {
        UserIdentity actor = CurrentUser.require();
        McpService.PublishResult result = mcps.publish(
                actor, id, request.version(), request.reportId(), request.expectedRevision(), request.idempotencyKey());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    // ------------------------------------------------------------------ Skill

    @GetMapping("/skills")
    @Operation(summary = "查询 Skill 列表")
    public Object listSkills() {
        return skills.list();
    }

    @PostMapping("/skills")
    @Operation(summary = "创建 Skill 草稿")
    public Object createSkill(@RequestBody SkillCreate request) {
        UserIdentity actor = CurrentUser.require();
        UUID id = skills.create(actor, request.key(), request.displayName(), request.description());
        return skills.require(id);
    }

    @PutMapping("/skills/{id}")
    public Object updateSkill(@PathVariable UUID id, @RequestBody SkillUpdate request) {
        return skills.update(
                id, request.expectedRevision(), request.displayName(), request.description(), request.enabled());
    }

    @DeleteMapping("/skills/{id}")
    public void deleteSkill(@PathVariable UUID id) {
        skills.delete(id);
    }

    @GetMapping("/skills/{id}/files")
    public Object skillFiles(@PathVariable UUID id) {
        return skills.fileList(id);
    }

    @GetMapping("/skills/{id}/file")
    @Operation(summary = "读取 Skill 草稿文件")
    public SkillDraftFile skillFile(
            @Parameter(description = "Skill 组件 ID") @PathVariable UUID id,
            @Parameter(description = "Skill 包内相对路径，例如 SKILL.md", example = "SKILL.md") @RequestParam String path) {
        return skills.file(id, path);
    }

    @PutMapping("/skills/{id}/file")
    @Operation(summary = "保存 Skill 草稿文件")
    public SkillDraftFile saveSkillFile(
            @PathVariable UUID id, @RequestParam String path, @RequestBody SkillFileSave request) {
        UserIdentity actor = CurrentUser.require();
        return skills.saveFile(actor, id, request.expectedRevision(), path, request.content());
    }

    @DeleteMapping("/skills/{id}/file")
    public void deleteSkillFile(@PathVariable UUID id, @RequestParam String path, @RequestParam long expectedRevision) {
        skills.deleteFile(id, expectedRevision, path);
    }

    @PostMapping("/skills/{id}/validate")
    @Operation(summary = "校验 Skill 草稿")
    public Object validateSkill(@Parameter(description = "Skill 组件 ID") @PathVariable UUID id) {
        return skills.validate(id);
    }

    @GetMapping("/skills/{id}/releases")
    public List<SkillRelease> skillReleases(@PathVariable UUID id) {
        return skills.releases(id);
    }

    @PostMapping("/skills/{id}/publish")
    @Operation(summary = "发布 Skill 版本", description = "冻结 Skill 文件包并异步注册组件版本")
    public ResponseEntity<SkillService.PublishResult> publishSkill(
            @PathVariable UUID id, @RequestBody SkillPublish request) {
        UserIdentity actor = CurrentUser.require();
        SkillService.PublishResult result =
                skills.publish(actor, id, request.version(), request.expectedRevision(), request.idempotencyKey());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    // ------------------------------------------------------------------ Agent

    @GetMapping("/agents")
    @Operation(summary = "查询 Agent 列表")
    public List<AgentComponent> listAgents() {
        return agents.list();
    }

    @GetMapping("/agents/{id}")
    public AgentService.AgentDetail agent(@PathVariable UUID id) {
        return agents.detail(id);
    }

    @PostMapping("/agents")
    @Operation(summary = "创建 Agent 草稿")
    public AgentComponent createAgent(@RequestBody AgentCreate request) {
        UserIdentity actor = CurrentUser.require();
        return agents.create(
                actor,
                request.key(),
                request.displayName(),
                request.description(),
                request.modelProfileId(),
                request.systemPrompt(),
                request.modelOptions(),
                request.budget(),
                mcpInputs(request.mcpBindings()),
                skillInputs(request.skillBindings()));
    }

    @PutMapping("/agents/{id}")
    public AgentComponent updateAgent(@PathVariable UUID id, @RequestBody AgentUpdate request) {
        return agents.update(
                id,
                request.expectedRevision(),
                request.displayName(),
                request.description(),
                request.modelProfileId(),
                request.systemPrompt(),
                request.modelOptions(),
                request.budget(),
                request.enabled(),
                request.mcpBindings() == null ? null : mcpInputs(request.mcpBindings()),
                request.skillBindings() == null ? null : skillInputs(request.skillBindings()));
    }

    @PutMapping("/agents/{id}/bindings")
    public AgentService.AgentDetail updateAgentBindings(@PathVariable UUID id, @RequestBody AgentBindings request) {
        AgentService.AgentDetail detail = agents.detail(id);
        agents.update(
                id,
                request.expectedRevision(),
                detail.agent().displayName(),
                detail.agent().description(),
                detail.agent().modelProfileId(),
                detail.agent().systemPrompt(),
                detail.agent().modelOptions(),
                detail.agent().budget(),
                detail.agent().enabled(),
                mcpInputs(request.mcpBindings()),
                skillInputs(request.skillBindings()));
        return agents.detail(id);
    }

    @PostMapping("/agents/{id}/validate")
    @Operation(summary = "编译校验 Agent")
    public Object validateAgent(@Parameter(description = "Agent 组件 ID") @PathVariable UUID id) {
        return agents.validate(id);
    }

    @GetMapping("/agents/{id}/releases")
    public List<AgentRelease> agentReleases(@PathVariable UUID id) {
        return agents.releases(id);
    }

    @PostMapping("/agents/{id}/publish")
    @Operation(summary = "发布 Agent 版本", description = "准备 Runtime 后激活指定版本")
    public ResponseEntity<AgentService.PublishResult> publishAgent(
            @PathVariable UUID id, @RequestBody AgentPublish request) {
        UserIdentity actor = CurrentUser.require();
        AgentService.PublishResult result =
                agents.publish(actor, id, request.version(), request.expectedRevision(), request.idempotencyKey());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    @PostMapping("/agents/{id}/rollback")
    @Operation(summary = "回滚 Agent 版本")
    public ResponseEntity<AgentService.RollbackResult> rollbackAgent(
            @PathVariable UUID id, @RequestBody AgentRollback request) {
        AgentService.RollbackResult result =
                agents.rollback(id, request.expectedRevision(), request.targetVersion(), request.idempotencyKey());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    @DeleteMapping("/agents/{id}")
    public void deleteAgent(@PathVariable UUID id) {
        agents.delete(id);
    }

    // ------------------------------------------------------------------ 发布与注册

    @GetMapping("/publications/{id}")
    @Operation(summary = "查询发布状态")
    public Publication publication(@Parameter(description = "发布记录 ID") @PathVariable UUID id) {
        return publications.find(id).orElseThrow(() -> AiExceptions.notFound("发布记录不存在"));
    }

    @PostMapping("/publications/{id}/retry")
    @Operation(summary = "重试失败的发布")
    public Publication retryPublication(@Parameter(description = "发布记录 ID") @PathVariable UUID id) {
        Publication publication = publications.find(id).orElseThrow(() -> AiExceptions.notFound("发布记录不存在"));
        publications.retry(id);
        return publications.find(id).orElseThrow(() -> AiExceptions.unavailable("发布记录暂时不可读"));
    }

    @GetMapping("/registry/components")
    public List<Map<String, Object>> registryComponents() {
        return registryStore.list().stream().map(this::registryView).toList();
    }

    @GetMapping("/registry/components/{type}/{key}/versions/{version}")
    public Map<String, Object> registryVersion(
            @PathVariable String type, @PathVariable String key, @PathVariable String version) {
        ComponentType componentType;
        try {
            componentType = ComponentType.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw AiExceptions.badRequest("组件类型只支持 MCP、SKILL、AGENT");
        }
        String serviceName = ComponentNames.serviceName(componentType, key, version);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("componentType", componentType.name());
        data.put("componentKey", key);
        data.put("componentVersion", version);
        data.put("serviceName", serviceName);
        data.put("checkedAt", Instant.now());
        UUID releaseId = releaseId(componentType, key, version);
        if (releaseId != null) {
            data.put("releaseId", releaseId);
            registryStore.find(componentType.name(), releaseId).ifPresent(projection -> {
                data.put("desiredState", projection.desiredState());
                data.put("observedState", projection.observedState());
                data.putIfAbsent("lastError", projection.lastError());
            });
        }
        if (componentType != ComponentType.AGENT) {
            String state = componentType == ComponentType.MCP
                    ? mcps.release(key, version).state()
                    : skills.release(key, version).state();
            data.put("authority", "PLATFORM");
            data.put("instances", List.of());
            data.put("healthy", "ACTIVE".equals(state));
            data.put("releaseState", state);
            return data;
        }
        List<Map<String, Object>> instances = registry.healthyInstances(serviceName).stream()
                .map(instance -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ip", instance.ip());
                    row.put("port", instance.port());
                    row.put("metadata", instance.metadata());
                    return row;
                })
                .toList();
        data.put("instances", instances);
        data.put("healthy", !instances.isEmpty());
        if (instances.isEmpty()) {
            data.put("error", registry.enabled() ? "没有健康实例" : "注册中心未启用");
        }
        return data;
    }

    private Map<String, Object> registryView(RegistryProjection projection) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", projection.id());
        row.put("resourceType", projection.resourceType());
        row.put("releaseId", projection.releaseId());
        row.put("serviceName", projection.serviceName());
        row.put("providerId", projection.providerId());
        row.put("desiredState", projection.desiredState());
        row.put("observedState", projection.observedState());
        row.put("ownerNode", projection.ownerNode());
        row.put("lastSuccessAt", projection.lastSuccessAt());
        row.put("lastError", projection.lastError());
        row.put("healthy", "READY".equals(projection.observedState()));
        componentCoordinates(projection).forEach(row::putIfAbsent);
        return row;
    }

    private Map<String, Object> componentCoordinates(RegistryProjection projection) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            switch (projection.resourceType()) {
                case "MCP" -> {
                    McpRelease release = mcps.release(projection.releaseId());
                    result.put("componentKey", release.componentKey());
                    result.put("componentVersion", release.version());
                }
                case "SKILL" -> {
                    SkillRelease release = skills.release(projection.releaseId());
                    result.put("componentKey", release.componentKey());
                    result.put("componentVersion", release.version());
                }
                case "AGENT" -> {
                    AgentRelease release = agents.release(projection.releaseId());
                    result.put("componentKey", release.agentKey());
                    result.put("componentVersion", release.version());
                }
                default -> {
                    return result;
                }
            }
        } catch (RuntimeException ignored) {
            // 注册投影可能保留已删除或过期 release；列表仍应可用。
        }
        return result;
    }

    private UUID releaseId(ComponentType type, String key, String version) {
        try {
            return switch (type) {
                case MCP -> mcps.release(key, version).id();
                case SKILL -> skills.release(key, version).id();
                case AGENT -> agents.release(key, version).id();
            };
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private List<McpBindingInput> mcpInputs(List<McpBindingRequest> bindings) {
        if (bindings == null) {
            return List.of();
        }
        return bindings.stream()
                .map(binding -> new McpBindingInput(
                        binding.mcpReleaseId() == null
                                ? mcps.requireActiveReleaseId(binding.componentKey(), binding.version())
                                : binding.mcpReleaseId(),
                        binding.toolName(),
                        binding.required() == null || binding.required(),
                        binding.executionOrder() == null ? 0 : binding.executionOrder()))
                .toList();
    }

    private List<SkillBindingInput> skillInputs(List<SkillBindingRequest> bindings) {
        if (bindings == null) {
            return List.of();
        }
        return bindings.stream()
                .map(binding -> new SkillBindingInput(
                        binding.skillReleaseId() == null
                                ? skills.requireActiveReleaseId(binding.componentKey(), binding.version())
                                : binding.skillReleaseId(),
                        binding.order() == null ? 0 : binding.order(),
                        binding.loadMode() == null ? "AUTO" : binding.loadMode()))
                .toList();
    }

    public record CredentialCreate(
            @Schema(description = "凭据唯一键") String key,
            @Schema(description = "凭据显示名称") String displayName,
            @Schema(description = "供应商 API 密钥；只在本次请求中传递") String secret) {}

    public record CredentialRotate(
            @Schema(description = "新的供应商 API 密钥") String secret) {}

    public record ModelCreate(
            @Schema(description = "模型配置唯一键") String key,
            @Schema(description = "模型显示名称") String displayName,
            @Schema(description = "用途说明") String description,
            @Schema(description = "OpenAI 兼容 API 根地址") String baseUrl,
            @Schema(description = "供应商模型名称") String modelName,
            @Schema(description = "关联的凭据 ID") UUID credentialId,
            @Schema(description = "模型调用参数") Map<String, Object> options) {}

    public record ModelUpdate(
            long expectedRevision,
            String displayName,
            String description,
            String baseUrl,
            String modelName,
            UUID credentialId,
            Map<String, Object> options,
            boolean enabled) {}

    public record McpCreate(
            String key,
            String displayName,
            String description,
            String origin,
            Map<String, Object> nativeRegistryRef,
            Map<String, Object> draft) {}

    public record McpUpdate(
            long expectedRevision,
            String displayName,
            String description,
            Map<String, Object> nativeRegistryRef,
            Map<String, Object> draft,
            boolean enabled) {}

    public record McpPublish(
            @Schema(description = "发布版本", example = "1.0.0") String version,
            @Schema(description = "MCP 连通性测试报告 ID") UUID reportId,
            @Schema(description = "草稿版本号，用于乐观锁") long expectedRevision,
            @Schema(description = "幂等键，用于发布请求重试") String idempotencyKey) {}

    public record McpToolPolicyUpdate(String toolName, String executionCategory) {}

    public record SkillCreate(String key, String displayName, String description) {}

    public record SkillUpdate(long expectedRevision, String displayName, String description, boolean enabled) {}

    public record SkillFileSave(String content, long expectedRevision) {}

    public record SkillPublish(String version, long expectedRevision, String idempotencyKey) {}

    public record McpBindingRequest(
            UUID mcpReleaseId,
            String componentKey,
            String version,
            String toolName,
            Boolean required,
            Integer executionOrder) {}

    public record SkillBindingRequest(
            UUID skillReleaseId, String componentKey, String version, Integer order, String loadMode) {}

    public record AgentCreate(
            @Schema(description = "Agent 唯一标识") String key,
            @Schema(description = "Agent 显示名称") String displayName,
            @Schema(description = "用途说明") String description,
            @Schema(description = "绑定的模型配置 ID") UUID modelProfileId,
            @Schema(description = "系统提示词") String systemPrompt,
            @Schema(description = "模型调用参数") Map<String, Object> modelOptions,
            @Schema(description = "Run 轮数、工具调用数和超时预算") Map<String, Object> budget,
            List<McpBindingRequest> mcpBindings,
            List<SkillBindingRequest> skillBindings) {}

    public record AgentUpdate(
            long expectedRevision,
            String displayName,
            String description,
            UUID modelProfileId,
            String systemPrompt,
            Map<String, Object> modelOptions,
            Map<String, Object> budget,
            boolean enabled,
            List<McpBindingRequest> mcpBindings,
            List<SkillBindingRequest> skillBindings) {}

    public record AgentBindings(
            long expectedRevision, List<McpBindingRequest> mcpBindings, List<SkillBindingRequest> skillBindings) {}

    public record AgentPublish(String version, long expectedRevision, String idempotencyKey) {}

    public record AgentRollback(String targetVersion, long expectedRevision, String idempotencyKey) {}
}
