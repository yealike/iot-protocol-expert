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
import top.yealike.pep.control.agents.domain.McpComponentView;
import top.yealike.pep.control.agents.domain.McpReleaseView;
import top.yealike.pep.control.agents.domain.McpTestResult;
import top.yealike.pep.control.agents.domain.McpToolCatalog;
import top.yealike.pep.control.agents.domain.McpToolPolicy;
import top.yealike.pep.control.agents.mcp.McpService;
import top.yealike.pep.control.agents.mcp.domain.PublishResult;
import top.yealike.pep.control.api.ai.admin.dto.McpCreate;
import top.yealike.pep.control.api.ai.admin.dto.McpPublish;
import top.yealike.pep.control.api.ai.admin.dto.McpToolPolicyUpdate;
import top.yealike.pep.control.api.ai.admin.dto.McpUpdate;
import top.yealike.pep.runtime.contract.OperationResult;
import top.yealike.pep.runtime.contract.UserIdentity;

/** MCP 草稿、连通性测试、工具审核与不可变发布。 */
@RestController
@RequestMapping("/api/ai/v1")
@PreAuthorize("hasAuthority('ai:admin')")
@Tag(name = "AI MCP 管理", description = "MCP 组件、工具目录、审核与发布")
public class McpAdminController {

    private final McpService mcps;

    public McpAdminController(McpService mcps) {
        this.mcps = mcps;
    }

    @GetMapping("/mcps")
    @Operation(summary = "查询 MCP 服务")
    public List<McpComponentView> listMcps() {
        return mcps.list();
    }

    @PostMapping("/mcps")
    @Operation(summary = "创建 MCP 草稿")
    public McpComponentView createMcp(@RequestBody McpCreate request) {
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
    public McpComponentView updateMcp(@PathVariable UUID id, @RequestBody McpUpdate request) {
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
    public OperationResult deleteMcp(@PathVariable UUID id) {
        mcps.delete(id);
        return new OperationResult(true, "DELETED");
    }

    @PostMapping("/mcps/{id}/test")
    @Operation(summary = "测试 MCP 连通性并读取工具目录")
    public McpTestResult testMcp(@Parameter(description = "MCP 组件 ID") @PathVariable UUID id) {
        return mcps.test(id);
    }

    @GetMapping("/mcps/{id}/tools")
    public McpToolCatalog mcpTools(@PathVariable UUID id) {
        return mcps.latestTools(id);
    }

    @GetMapping("/mcps/{id}/tool-policies")
    public List<McpToolPolicy> mcpToolPolicies(@PathVariable UUID id) {
        return mcps.toolPolicies(id);
    }

    @PutMapping("/mcps/{id}/tool-policies")
    public McpToolPolicy reviewMcpTool(@PathVariable UUID id, @RequestBody McpToolPolicyUpdate request) {
        UserIdentity actor = CurrentUser.require();
        return mcps.reviewTool(actor, id, request.toolName(), request.executionCategory());
    }

    @GetMapping("/mcps/{id}/releases")
    public List<McpReleaseView> mcpReleases(@PathVariable UUID id) {
        return mcps.releaseViews(id);
    }

    @PostMapping("/mcps/{id}/publish")
    @Operation(summary = "发布 MCP 版本", description = "冻结工具目录并异步注册组件版本")
    public ResponseEntity<PublishResult> publishMcp(@PathVariable UUID id, @RequestBody McpPublish request) {
        UserIdentity actor = CurrentUser.require();
        PublishResult result = mcps.publish(
                actor, id, request.version(), request.reportId(), request.expectedRevision(), request.idempotencyKey());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }
}
