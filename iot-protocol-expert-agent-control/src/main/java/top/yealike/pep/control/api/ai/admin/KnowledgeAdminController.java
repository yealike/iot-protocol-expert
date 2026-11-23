package top.yealike.pep.control.api.ai.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.control.access.CurrentUser;
import top.yealike.pep.control.api.ai.admin.dto.IndexRequest;
import top.yealike.pep.control.audit.AiAuditLogMapper;
import top.yealike.pep.control.knowledge.KnowledgeService;
import top.yealike.pep.control.knowledge.domain.DocumentRow;
import top.yealike.pep.control.knowledge.domain.IngestReport;
import top.yealike.pep.control.knowledge.domain.KnowledgePreviewItem;
import top.yealike.pep.runtime.contract.OperationResult;
import top.yealike.pep.runtime.contract.UserIdentity;

/** 协议知识文档的索引、预览、发布与归档。 */
@RestController
@RequestMapping("/api/ai/v1")
@Tag(name = "AI 知识管理", description = "协议文档索引、预览和发布")
public class KnowledgeAdminController {

    private final KnowledgeService knowledge;
    private final AiAuditLogMapper auditLog;

    public KnowledgeAdminController(KnowledgeService knowledge, AiAuditLogMapper auditLog) {
        this.knowledge = knowledge;
        this.auditLog = auditLog;
    }

    @GetMapping("/knowledge/documents")
    @Operation(summary = "查询已索引协议文档")
    @PreAuthorize("hasAuthority('ai:admin')")
    public List<DocumentRow> knowledgeDocuments() {
        CurrentUser.require();
        return knowledge.documents();
    }

    @PostMapping("/knowledge/index")
    @Operation(summary = "执行协议文档索引")
    @PreAuthorize("hasAuthority('ai:admin')")
    public IngestReport index(@RequestBody(required = false) IndexRequest request) {
        UserIdentity identity = CurrentUser.require();
        boolean publish = request != null && Boolean.TRUE.equals(request.publish());
        var report = knowledge.ingestAll(identity, publish);
        audit(identity, "KNOWLEDGE_INDEX", "all", report.failed() == 0 ? "SUCCESS" : "PARTIAL");
        return report;
    }

    @GetMapping("/knowledge/documents/{id}/preview")
    @Operation(summary = "预览知识文档")
    @PreAuthorize("hasAuthority('ai:admin')")
    public List<KnowledgePreviewItem> preview(@Parameter(description = "知识文档 ID") @PathVariable UUID id) {
        return knowledge.preview(id);
    }

    @PostMapping("/knowledge/documents/{id}/publish")
    @Operation(summary = "发布知识文档")
    @PreAuthorize("hasAuthority('ai:admin')")
    public OperationResult publish(@Parameter(description = "知识文档 ID") @PathVariable UUID id) {
        knowledge.publish(id);
        audit(CurrentUser.require(), "KNOWLEDGE_PUBLISH", id.toString(), "SUCCESS");
        return new OperationResult(true, "PUBLISHED");
    }

    @PostMapping("/knowledge/documents/{id}/archive")
    @Operation(summary = "归档知识文档")
    @PreAuthorize("hasAuthority('ai:admin')")
    public OperationResult archive(@Parameter(description = "知识文档 ID") @PathVariable UUID id) {
        knowledge.archive(id);
        audit(CurrentUser.require(), "KNOWLEDGE_ARCHIVE", id.toString(), "SUCCESS");
        return new OperationResult(true, "ARCHIVED");
    }

    private void audit(UserIdentity identity, String action, String target, String result) {
        auditLog.audit(identity.username(), action, target, result);
    }
}
