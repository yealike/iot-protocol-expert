package top.yealike.ipe.ai.api;

import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.ipe.ai.identity.CurrentUser;
import top.yealike.ipe.ai.identity.UserIdentity;
import top.yealike.ipe.ai.knowledge.KnowledgeService;
import top.yealike.ipe.ai.mapper.AuditLogMapper;
import top.yealike.ipe.ai.policy.PolicyService;

/** 知识索引与策略管理。管理类接口要求 ADMIN，权限不自动等于可看所有业务数据。 */
@RestController
@RequestMapping("/api/ai/v1")
public class AdminController {

    private final PolicyService policy;
    private final KnowledgeService knowledge;
    private final AuditLogMapper mapper;

    public AdminController(PolicyService policy, KnowledgeService knowledge, AuditLogMapper mapper) {
        this.policy = policy;
        this.knowledge = knowledge;
        this.mapper = mapper;
    }

    public record IndexRequest(Boolean publish) {}

    @GetMapping("/knowledge/stats")
    @PreAuthorize("hasAuthority('ai:admin')")
    public Map<String, Object> knowledgeStats() {
        CurrentUser.require();
        return knowledge.stats();
    }

    @GetMapping("/knowledge/documents")
    @PreAuthorize("hasAuthority('ai:admin')")
    public Object knowledgeDocuments() {
        CurrentUser.require();
        return knowledge.documents();
    }

    @PostMapping("/knowledge/index")
    @PreAuthorize("hasAuthority('ai:admin')")
    public Object index(@RequestBody(required = false) IndexRequest request) {
        UserIdentity identity = CurrentUser.require();
        boolean publish = request != null && Boolean.TRUE.equals(request.publish());
        var report = knowledge.ingestAll(identity, publish);
        audit(identity, "KNOWLEDGE_INDEX", "all", report.failed() == 0 ? "SUCCESS" : "PARTIAL");
        return report;
    }

    @GetMapping("/knowledge/documents/{id}/preview")
    @PreAuthorize("hasAuthority('ai:admin')")
    public Object preview(@PathVariable UUID id) {
        return knowledge.preview(id);
    }

    @PostMapping("/knowledge/documents/{id}/publish")
    @PreAuthorize("hasAuthority('ai:admin')")
    public Map<String, Object> publish(@PathVariable UUID id) {
        knowledge.publish(id);
        audit(CurrentUser.require(), "KNOWLEDGE_PUBLISH", id.toString(), "SUCCESS");
        return Map.of("ok", true, "status", "PUBLISHED");
    }

    @PostMapping("/knowledge/documents/{id}/archive")
    @PreAuthorize("hasAuthority('ai:admin')")
    public Map<String, Object> archive(@PathVariable UUID id) {
        knowledge.archive(id);
        audit(CurrentUser.require(), "KNOWLEDGE_ARCHIVE", id.toString(), "SUCCESS");
        return Map.of("ok", true, "status", "ARCHIVED");
    }

    @GetMapping("/admin/policies")
    @PreAuthorize("hasAuthority('ai:admin')")
    public Map<String, Object> policies() {
        return policy.describe();
    }

    @PostMapping("/admin/policies/reload")
    @PreAuthorize("hasAuthority('ai:admin')")
    public Map<String, Object> reload() {
        policy.reload();
        return Map.of("ok", true, "version", policy.version());
    }

    private void audit(UserIdentity identity, String action, String target, String result) {
        mapper.audit(identity.username(), action, target, result);
    }
}
