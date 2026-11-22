package top.yealike.pep.control.api.ai.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
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
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.control.access.CurrentUser;
import top.yealike.pep.control.agents.AgentService;
import top.yealike.pep.control.agents.domain.AgentComponent;
import top.yealike.pep.control.agents.domain.AgentRelease;
import top.yealike.pep.control.agents.mcp.McpService;
import top.yealike.pep.control.agents.skill.SkillService;
import top.yealike.pep.control.agents.store.AgentStore.McpBindingInput;
import top.yealike.pep.control.agents.store.AgentStore.SkillBindingInput;
import top.yealike.pep.runtime.contract.UserIdentity;

/** Agent 草稿编排、编译校验、发布与回滚。 */
@RestController
@RequestMapping("/api/ai/v1")
@PreAuthorize("hasAuthority('ai:admin')")
@Tag(name = "AI Agent 管理", description = "Agent 模型、提示词、MCP/Skill 绑定与发布")
public class AgentAdminController {

    private final AgentService agents;
    private final McpService mcps;
    private final SkillService skills;

    public AgentAdminController(AgentService agents, McpService mcps, SkillService skills) {
        this.agents = agents;
        this.mcps = mcps;
        this.skills = skills;
    }

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
