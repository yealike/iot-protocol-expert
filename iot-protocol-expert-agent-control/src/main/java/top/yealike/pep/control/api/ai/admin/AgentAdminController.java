package top.yealike.pep.control.api.ai.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
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
import top.yealike.pep.control.agents.domain.AgentComponentView;
import top.yealike.pep.control.agents.domain.AgentDetailView;
import top.yealike.pep.control.agents.domain.CompileResult;
import top.yealike.pep.control.agents.domain.McpBindingInput;
import top.yealike.pep.control.agents.domain.PublishResult;
import top.yealike.pep.control.agents.domain.RollbackResult;
import top.yealike.pep.control.agents.domain.SkillBindingInput;
import top.yealike.pep.control.agents.mcp.McpService;
import top.yealike.pep.control.agents.skill.SkillService;
import top.yealike.pep.control.api.ai.admin.dto.AgentCreate;
import top.yealike.pep.control.api.ai.admin.dto.AgentPublish;
import top.yealike.pep.control.api.ai.admin.dto.AgentRollback;
import top.yealike.pep.control.api.ai.admin.dto.AgentUpdate;
import top.yealike.pep.control.api.ai.admin.dto.McpBindingRequest;
import top.yealike.pep.control.api.ai.admin.dto.SkillBindingRequest;
import top.yealike.pep.runtime.contract.OperationResult;
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
    public List<AgentComponentView> listAgents() {
        return agents.list();
    }

    @GetMapping("/agents/{id}")
    public AgentDetailView agent(@PathVariable UUID id) {
        return agents.detail(id);
    }

    @PostMapping("/agents")
    @Operation(summary = "创建 Agent 草稿")
    public AgentComponentView createAgent(@RequestBody AgentCreate request) {
        UserIdentity actor = CurrentUser.require();
        AgentComponent created = agents.create(
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
        return AgentComponentView.from(created);
    }

    @PutMapping("/agents/{id}")
    public AgentComponentView updateAgent(@PathVariable UUID id, @RequestBody AgentUpdate request) {
        AgentComponent updated = agents.update(
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
        return AgentComponentView.from(updated);
    }

    @PostMapping("/agents/{id}/validate")
    @Operation(summary = "编译校验 Agent")
    public CompileResult validateAgent(@Parameter(description = "Agent 组件 ID") @PathVariable UUID id) {
        return agents.validate(id);
    }

    @PostMapping("/agents/{id}/publish")
    @Operation(summary = "发布 Agent 版本", description = "准备 Runtime 后激活指定版本")
    public ResponseEntity<PublishResult> publishAgent(@PathVariable UUID id, @RequestBody AgentPublish request) {
        UserIdentity actor = CurrentUser.require();
        PublishResult result =
                agents.publish(actor, id, request.version(), request.expectedRevision(), request.idempotencyKey());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    @PostMapping("/agents/{id}/rollback")
    @Operation(summary = "回滚 Agent 版本")
    public ResponseEntity<RollbackResult> rollbackAgent(@PathVariable UUID id, @RequestBody AgentRollback request) {
        RollbackResult result =
                agents.rollback(id, request.expectedRevision(), request.targetVersion(), request.idempotencyKey());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    @DeleteMapping("/agents/{id}")
    public OperationResult deleteAgent(@PathVariable UUID id) {
        agents.delete(id);
        return new OperationResult(true, "DELETED");
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
}
