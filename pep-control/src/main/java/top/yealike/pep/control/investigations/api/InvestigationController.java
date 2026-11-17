package top.yealike.pep.control.investigations.api;

import io.swagger.v3.oas.annotations.Operation;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.control.investigations.InvestigationRecords.Approval;
import top.yealike.pep.control.investigations.InvestigationRecords.Conclusion;
import top.yealike.pep.control.investigations.InvestigationRecords.Evidence;
import top.yealike.pep.control.investigations.InvestigationRecords.Experiment;
import top.yealike.pep.control.investigations.InvestigationRecords.Hypothesis;
import top.yealike.pep.control.investigations.InvestigationRecords.Task;
import top.yealike.pep.control.investigations.InvestigationRecords.TaskEvent;
import top.yealike.pep.control.investigations.InvestigationService;

/** AI Native 调查任务 API：目标、证据、假设、实验、结论与人工确认。 */
@RestController
@RequestMapping(path = "/api/control/v1/investigations", produces = "application/json")
public class InvestigationController {

    private final InvestigationService investigations;

    public InvestigationController(InvestigationService investigations) {
        this.investigations = investigations;
    }

    public record CreateTaskRequest(UUID projectId, String title, String goal, Map<String, Object> scope) {}

    public record TransitionRequest(long expectedVersion, String status, String reason) {}

    public record EvidenceRequest(
            String sourceType,
            String sourceId,
            String sourceVersion,
            String summary,
            String rawRef,
            Map<String, Object> scope,
            OffsetDateTime collectedAt,
            OffsetDateTime expiresAt,
            Map<String, Object> metadata) {}

    public record HypothesisRequest(String statement, List<String> supportRefs) {}

    public record HypothesisStatusRequest(String status) {}

    public record ExperimentRequest(String scenarioReleaseId, Map<String, Object> expectedAssertion) {}

    public record ExperimentUpdateRequest(String status, String resultSummary, String resultRef, String simRunId) {}

    public record ConclusionRequest(String conclusion, List<UUID> evidenceIds, String validationStatus) {}

    public record ApprovalRequest(String actionType, Map<String, Object> target, OffsetDateTime expiresAt) {}

    @Operation(summary = "创建调查任务")
    @PostMapping
    public Task create(@RequestBody CreateTaskRequest request) {
        return investigations.create(request.projectId(), request.title(), request.goal(), request.scope());
    }

    @Operation(summary = "列出项目内可见的调查任务")
    @GetMapping
    public List<Task> list(
            @RequestParam UUID projectId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        return investigations.list(projectId, status, limit);
    }

    @Operation(summary = "读取调查任务")
    @GetMapping("/{id}")
    public Task get(@PathVariable UUID id) {
        return investigations.require(id);
    }

    @Operation(summary = "任务状态跃迁")
    @PostMapping("/{id}/transitions")
    public Task transition(@PathVariable UUID id, @RequestBody TransitionRequest request) {
        return investigations.transition(id, request.expectedVersion(), request.status(), request.reason());
    }

    @Operation(summary = "按游标读取任务事件")
    @GetMapping("/{id}/events")
    public List<TaskEvent> events(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") long afterSeq,
            @RequestParam(defaultValue = "100") int limit) {
        return investigations.events(id, afterSeq, limit);
    }

    @Operation(summary = "追加不可变证据快照")
    @PostMapping("/{id}/evidence")
    public Evidence addEvidence(@PathVariable UUID id, @RequestBody EvidenceRequest request) {
        return investigations.addEvidence(
                id,
                request.sourceType(),
                request.sourceId(),
                request.sourceVersion(),
                request.summary(),
                request.rawRef(),
                request.scope(),
                request.collectedAt(),
                request.expiresAt(),
                request.metadata());
    }

    @Operation(summary = "列出任务证据")
    @GetMapping("/{id}/evidence")
    public List<Evidence> evidence(@PathVariable UUID id) {
        return investigations.listEvidence(id);
    }

    @Operation(summary = "提出诊断假设")
    @PostMapping("/{id}/hypotheses")
    public Hypothesis addHypothesis(@PathVariable UUID id, @RequestBody HypothesisRequest request) {
        return investigations.addHypothesis(id, request.statement(), request.supportRefs());
    }

    @Operation(summary = "列出诊断假设")
    @GetMapping("/{id}/hypotheses")
    public List<Hypothesis> hypotheses(@PathVariable UUID id) {
        return investigations.listHypotheses(id);
    }

    @Operation(summary = "更新假设状态")
    @PatchMapping("/{id}/hypotheses/{hypothesisId}")
    public Hypothesis updateHypothesis(
            @PathVariable UUID id, @PathVariable UUID hypothesisId, @RequestBody HypothesisStatusRequest request) {
        return investigations.updateHypothesis(id, hypothesisId, request.status());
    }

    @Operation(summary = "登记模拟实验引用")
    @PostMapping("/{id}/experiments")
    public Experiment addExperiment(@PathVariable UUID id, @RequestBody ExperimentRequest request) {
        return investigations.addExperiment(id, request.scenarioReleaseId(), request.expectedAssertion());
    }

    @Operation(summary = "列出模拟实验")
    @GetMapping("/{id}/experiments")
    public List<Experiment> experiments(@PathVariable UUID id) {
        return investigations.listExperiments(id);
    }

    @Operation(summary = "回写实验结果")
    @PatchMapping("/{id}/experiments/{experimentId}")
    public Experiment updateExperiment(
            @PathVariable UUID id, @PathVariable UUID experimentId, @RequestBody ExperimentUpdateRequest request) {
        return investigations.updateExperiment(
                id, experimentId, request.status(), request.resultSummary(), request.resultRef(), request.simRunId());
    }

    @Operation(summary = "追加结论版本")
    @PostMapping("/{id}/conclusions")
    public Conclusion addConclusion(@PathVariable UUID id, @RequestBody ConclusionRequest request) {
        return investigations.addConclusion(
                id, request.conclusion(), request.evidenceIds(), request.validationStatus());
    }

    @Operation(summary = "列出结论版本")
    @GetMapping("/{id}/conclusions")
    public List<Conclusion> conclusions(@PathVariable UUID id) {
        return investigations.listConclusions(id);
    }

    @Operation(summary = "申请参数绑定的人工确认")
    @PostMapping("/{id}/approvals")
    public Approval requestApproval(@PathVariable UUID id, @RequestBody ApprovalRequest request) {
        return investigations.requestApproval(id, request.actionType(), request.target(), request.expiresAt());
    }

    @Operation(summary = "列出人工确认")
    @GetMapping("/{id}/approvals")
    public List<Approval> approvals(@PathVariable UUID id) {
        return investigations.listApprovals(id);
    }

    @Operation(summary = "使用一次人工确认")
    @PostMapping("/{id}/approvals/{approvalId}/use")
    public Approval useApproval(@PathVariable UUID id, @PathVariable UUID approvalId) {
        return investigations.useApproval(id, approvalId);
    }
}
