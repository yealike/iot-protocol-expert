package top.yealike.pep.control.api.ai;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.control.access.CurrentUser;
import top.yealike.pep.control.access.UserIdentity;
import top.yealike.pep.control.access.policy.PolicyService;
import top.yealike.pep.control.agents.mapper.AiAuditLogMapper;
import top.yealike.pep.control.knowledge.KnowledgeService;

/** 知识索引与策略管理。管理类接口要求 ADMIN，权限不自动等于可看所有业务数据。 */
@RestController
@RequestMapping("/api/ai/v1")
@Tag(name = "AI 知识与策略", description = "协议文档索引、知识发布和权限策略管理")
public class AdminController {

    private final PolicyService policy;
    private final KnowledgeService knowledge;
    private final AiAuditLogMapper mapper;

    public AdminController(PolicyService policy, KnowledgeService knowledge, AiAuditLogMapper mapper) {
        this.policy = policy;
        this.knowledge = knowledge;
        this.mapper = mapper;
    }

    public record IndexRequest(
            @Schema(description = "索引完成后是否直接发布") Boolean publish) {}

    @GetMapping("/knowledge/stats")
    @Operation(summary = "查询知识库索引统计")
    @PreAuthorize("hasAuthority('ai:admin')")
    public Map<String, Object> knowledgeStats() {
        CurrentUser.require();
        return knowledge.stats();
    }

    @GetMapping("/knowledge/documents")
    @Operation(summary = "查询已索引协议文档")
    @PreAuthorize("hasAuthority('ai:admin')")
    public Object knowledgeDocuments() {
        CurrentUser.require();
        return knowledge.documents();
    }

    @PostMapping("/knowledge/index")
    @Operation(summary = "执行协议文档索引")
    @PreAuthorize("hasAuthority('ai:admin')")
    public Object index(@RequestBody(required = false) IndexRequest request) {
        UserIdentity identity = CurrentUser.require();
        boolean publish = request != null && Boolean.TRUE.equals(request.publish());
        var report = knowledge.ingestAll(identity, publish);
        audit(identity, "KNOWLEDGE_INDEX", "all", report.failed() == 0 ? "SUCCESS" : "PARTIAL");
        return report;
    }

    @GetMapping("/knowledge/documents/{id}/preview")
    @Operation(summary = "预览知识文档")
    @PreAuthorize("hasAuthority('ai:admin')")
    public Object preview(@Parameter(description = "知识文档 ID") @PathVariable UUID id) {
        return knowledge.preview(id);
    }

    @PostMapping("/knowledge/documents/{id}/publish")
    @Operation(summary = "发布知识文档")
    @PreAuthorize("hasAuthority('ai:admin')")
    public Map<String, Object> publish(@Parameter(description = "知识文档 ID") @PathVariable UUID id) {
        knowledge.publish(id);
        audit(CurrentUser.require(), "KNOWLEDGE_PUBLISH", id.toString(), "SUCCESS");
        return Map.of("ok", true, "status", "PUBLISHED");
    }

    @PostMapping("/knowledge/documents/{id}/archive")
    @Operation(summary = "归档知识文档")
    @PreAuthorize("hasAuthority('ai:admin')")
    public Map<String, Object> archive(@Parameter(description = "知识文档 ID") @PathVariable UUID id) {
        knowledge.archive(id);
        audit(CurrentUser.require(), "KNOWLEDGE_ARCHIVE", id.toString(), "SUCCESS");
        return Map.of("ok", true, "status", "ARCHIVED");
    }

    @GetMapping("/admin/policies")
    @Operation(summary = "查看当前权限策略")
    @PreAuthorize("hasAuthority('ai:admin')")
    public Map<String, Object> policies() {
        return policy.describe();
    }

    @PostMapping("/admin/policies/reload")
    @Operation(summary = "重新加载权限策略")
    @PreAuthorize("hasAuthority('ai:admin')")
    public Map<String, Object> reload() {
        policy.reload();
        return Map.of("ok", true, "version", policy.version());
    }

    private void audit(UserIdentity identity, String action, String target, String result) {
        mapper.audit(identity.username(), action, target, result);
    }
}
