package top.yealike.pep.control.investigations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.pep.control.access.AccessService;
import top.yealike.pep.control.access.UserIdentity;
import top.yealike.pep.control.investigations.InvestigationRecords.Approval;
import top.yealike.pep.control.investigations.InvestigationRecords.Conclusion;
import top.yealike.pep.control.investigations.InvestigationRecords.Evidence;
import top.yealike.pep.control.investigations.InvestigationRecords.Experiment;
import top.yealike.pep.control.investigations.InvestigationRecords.Hypothesis;
import top.yealike.pep.control.investigations.InvestigationRecords.Task;
import top.yealike.pep.control.investigations.InvestigationRecords.TaskEvent;
import top.yealike.pep.control.investigations.mapper.InvestigationMapper;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.control.support.JsonSupport;

/**
 * 调查任务应用服务：任务与 Run 分离，状态跃迁、证据、假设、实验和结论都保留可复核轨迹。
 * 所有读取和写入先通过项目作用域授权。
 */
@Service
public class InvestigationService {

    public static final String OPEN = "OPEN";
    public static final String INVESTIGATING = "INVESTIGATING";
    public static final String WAITING_USER = "WAITING_USER";
    public static final String WAITING_APPROVAL = "WAITING_APPROVAL";
    public static final String WAITING_QUOTA = "WAITING_QUOTA";
    public static final String VALIDATING = "VALIDATING";
    public static final String RESOLVED = "RESOLVED";
    public static final String INCONCLUSIVE = "INCONCLUSIVE";
    public static final String CANCELLED = "CANCELLED";

    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            OPEN, Set.of(INVESTIGATING, CANCELLED),
            INVESTIGATING,
                    Set.of(
                            WAITING_USER,
                            WAITING_APPROVAL,
                            WAITING_QUOTA,
                            VALIDATING,
                            RESOLVED,
                            INCONCLUSIVE,
                            CANCELLED),
            WAITING_USER, Set.of(INVESTIGATING, CANCELLED),
            WAITING_APPROVAL, Set.of(INVESTIGATING, CANCELLED),
            WAITING_QUOTA, Set.of(INVESTIGATING, CANCELLED),
            VALIDATING, Set.of(INVESTIGATING, RESOLVED, INCONCLUSIVE, CANCELLED));
    private static final Set<String> HYPOTHESIS_STATES = Set.of("OPEN", "SUPPORTED", "REFUTED", "CLOSED");
    private static final Set<String> EXPERIMENT_STATES =
            Set.of("REQUESTED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED");
    private static final Set<String> VALIDATION_STATES = Set.of("UNVERIFIED", "VERIFIED", "REJECTED");

    private final InvestigationMapper tasks;
    private final AccessService access;

    public InvestigationService(InvestigationMapper tasks, AccessService access) {
        this.tasks = tasks;
        this.access = access;
    }

    @Transactional
    public Task create(UUID projectId, String title, String goal, Map<String, Object> scope) {
        var accessScope = access.requireProject(projectId, "investigation:create");
        String normalizedTitle = requireText(title, "调查目标标题", 256);
        String normalizedGoal = requireText(goal, "调查目标", 20_000);
        UUID id = UUID.randomUUID();
        Task task = new Task(
                id,
                accessScope.tenantId(),
                projectId,
                accessScope.actor().id(),
                normalizedTitle,
                normalizedGoal,
                JsonSupport.write(scope == null ? Map.of() : scope),
                OPEN,
                0,
                null,
                null);
        tasks.insertTask(task);
        appendEvent(id, "TASK_CREATED", Map.of("title", normalizedTitle, "status", OPEN), accessScope.actor());
        return require(id);
    }

    public List<Task> list(UUID projectId, String status, int limit) {
        var scope = access.requireProject(projectId, "investigation:create");
        boolean allRead = access.canReadAll(scope.actor(), scope.tenantId(), projectId);
        String normalized =
                status == null || status.isBlank() ? null : status.strip().toUpperCase(java.util.Locale.ROOT);
        return tasks.listTasks(projectId, scope.actor().id(), allRead, normalized, clamp(limit));
    }

    public Task require(UUID id) {
        Task task = tasks.findTask(id);
        if (task == null) {
            throw AiExceptions.notFound("调查任务不存在");
        }
        access.requireTask(task.projectId(), task.ownerId(), "investigation:read");
        return task;
    }

    @Transactional
    public Task transition(UUID id, long expectedVersion, String targetStatus, String reason) {
        Task task = requireForWrite(id);
        String target = requireText(targetStatus, "目标状态", 32).toUpperCase(java.util.Locale.ROOT);
        if (!TRANSITIONS.getOrDefault(task.status(), Set.of()).contains(target)) {
            throw AiExceptions.conflict("INVALID_TRANSITION", "不允许从 " + task.status() + " 转为 " + target);
        }
        int updated = tasks.updateTaskStatus(id, expectedVersion, target);
        if (updated == 0) {
            throw AiExceptions.conflict("REVISION_CONFLICT", "任务已被其他请求修改，请刷新后重试");
        }
        appendEvent(
                id,
                "STATUS_CHANGED",
                Map.of(
                        "from",
                        task.status(),
                        "to",
                        target,
                        "reason",
                        reason == null ? "" : reason.strip(),
                        "version",
                        expectedVersion + 1),
                access.requireProject(task.projectId()).actor());
        return require(id);
    }

    public List<TaskEvent> events(UUID id, long afterSeq, int limit) {
        require(id);
        return tasks.listEvents(id, Math.max(0, afterSeq), clamp(limit));
    }

    @Transactional
    public Evidence addEvidence(
            UUID taskId,
            String sourceType,
            String sourceId,
            String sourceVersion,
            String summary,
            String rawRef,
            Map<String, Object> scope,
            OffsetDateTime collectedAt,
            OffsetDateTime expiresAt,
            Map<String, Object> metadata) {
        Task task = requireForWrite(taskId);
        String type = requireText(sourceType, "证据来源类型", 32).toUpperCase(java.util.Locale.ROOT);
        String source = requireText(sourceId, "证据来源 ID", 128);
        String normalizedSummary = requireText(summary, "证据摘要", 20_000);
        if (collectedAt == null) {
            throw AiExceptions.badRequest("证据采集时间不能为空");
        }
        if (expiresAt != null && !expiresAt.isAfter(collectedAt)) {
            throw AiExceptions.badRequest("证据过期时间必须晚于采集时间");
        }
        Map<String, Object> normalizedMetadata = metadata == null ? Map.of() : metadata;
        String hash = sha256(type + "\n" + source + "\n" + normalizedSummary + "\n" + (rawRef == null ? "" : rawRef)
                + "\n" + JsonSupport.write(normalizedMetadata));
        Evidence existing = tasks.findEvidenceByHash(task.projectId(), type, source, hash);
        UUID evidenceId = existing == null ? UUID.randomUUID() : existing.id();
        if (existing == null) {
            tasks.insertEvidence(new Evidence(
                    evidenceId,
                    task.tenantId(),
                    task.projectId(),
                    type,
                    source,
                    sourceVersion,
                    hash,
                    normalizedSummary,
                    rawRef,
                    JsonSupport.write(scope == null ? Map.of() : scope),
                    collectedAt,
                    expiresAt,
                    JsonSupport.write(normalizedMetadata),
                    null));
        }
        tasks.linkEvidence(
                taskId,
                evidenceId,
                access.requireProject(task.projectId()).actor().id());
        appendEvent(
                taskId,
                "EVIDENCE_ADDED",
                Map.of("evidenceId", evidenceId.toString(), "sourceType", type, "sourceId", source),
                access.requireProject(task.projectId()).actor());
        return listEvidence(taskId).stream()
                .filter(item -> item.id().equals(evidenceId))
                .findFirst()
                .orElseThrow();
    }

    public List<Evidence> listEvidence(UUID taskId) {
        require(taskId);
        return tasks.listEvidence(taskId);
    }

    @Transactional
    public Hypothesis addHypothesis(UUID taskId, String statement, List<String> supportRefs) {
        Task task = requireForWrite(taskId);
        String normalized = requireText(statement, "假设", 20_000);
        UUID id = UUID.randomUUID();
        tasks.insertHypothesis(new Hypothesis(
                id,
                taskId,
                normalized,
                "OPEN",
                JsonSupport.write(supportRefs == null ? List.of() : supportRefs),
                access.requireProject(task.projectId()).actor().id(),
                null,
                null));
        appendEvent(
                taskId,
                "HYPOTHESIS_ADDED",
                Map.of("hypothesisId", id.toString()),
                access.requireProject(task.projectId()).actor());
        return tasks.listHypotheses(taskId).stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    public List<Hypothesis> listHypotheses(UUID taskId) {
        require(taskId);
        return tasks.listHypotheses(taskId);
    }

    @Transactional
    public Hypothesis updateHypothesis(UUID taskId, UUID hypothesisId, String status) {
        Task task = requireForWrite(taskId);
        String normalized = requireText(status, "假设状态", 16).toUpperCase(java.util.Locale.ROOT);
        if (!HYPOTHESIS_STATES.contains(normalized)) {
            throw AiExceptions.badRequest("假设状态无效：" + normalized);
        }
        if (tasks.updateHypothesisStatus(taskId, hypothesisId, normalized) == 0) {
            throw AiExceptions.notFound("假设不存在");
        }
        appendEvent(
                taskId,
                "HYPOTHESIS_UPDATED",
                Map.of("hypothesisId", hypothesisId.toString(), "status", normalized),
                access.requireProject(task.projectId()).actor());
        return tasks.listHypotheses(taskId).stream()
                .filter(item -> item.id().equals(hypothesisId))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public Experiment addExperiment(UUID taskId, String scenarioReleaseId, Map<String, Object> expectedAssertion) {
        Task task = requireForWrite(taskId);
        String scenario = requireText(scenarioReleaseId, "场景版本", 128);
        UUID id = UUID.randomUUID();
        tasks.insertExperiment(new Experiment(
                id,
                taskId,
                scenario,
                null,
                "REQUESTED",
                null,
                null,
                JsonSupport.write(expectedAssertion == null ? Map.of() : expectedAssertion),
                access.requireProject(task.projectId()).actor().id(),
                null,
                null));
        appendEvent(
                taskId,
                "EXPERIMENT_REQUESTED",
                Map.of("experimentId", id.toString(), "scenarioReleaseId", scenario),
                access.requireProject(task.projectId()).actor());
        return tasks.listExperiments(taskId).stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    public List<Experiment> listExperiments(UUID taskId) {
        require(taskId);
        return tasks.listExperiments(taskId);
    }

    @Transactional
    public Experiment updateExperiment(
            UUID taskId, UUID experimentId, String status, String resultSummary, String resultRef, String simRunId) {
        requireForWrite(taskId);
        String normalized = requireText(status, "实验状态", 16).toUpperCase(java.util.Locale.ROOT);
        if (!EXPERIMENT_STATES.contains(normalized)) {
            throw AiExceptions.badRequest("实验状态无效：" + normalized);
        }
        if (tasks.updateExperiment(taskId, experimentId, normalized, simRunId, resultSummary, resultRef) == 0) {
            throw AiExceptions.notFound("实验不存在");
        }
        appendEvent(
                taskId,
                "EXPERIMENT_UPDATED",
                Map.of("experimentId", experimentId.toString(), "status", normalized),
                access.requireProject(tasks.findTask(taskId).projectId()).actor());
        return tasks.listExperiments(taskId).stream()
                .filter(item -> item.id().equals(experimentId))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public Conclusion addConclusion(UUID taskId, String conclusion, List<UUID> evidenceIds, String validationStatus) {
        Task task = requireForWrite(taskId);
        String normalizedConclusion = requireText(conclusion, "调查结论", 20_000);
        String validation = requireText(validationStatus, "验证状态", 16).toUpperCase(java.util.Locale.ROOT);
        if (!VALIDATION_STATES.contains(validation)) {
            throw AiExceptions.badRequest("验证状态无效：" + validation);
        }
        List<UUID> refs = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        if (refs.isEmpty() && "VERIFIED".equals(validation)) {
            throw AiExceptions.badRequest("已验证结论必须引用证据");
        }
        if (!refs.isEmpty() && tasks.countLinkedEvidence(taskId, refs) != refs.size()) {
            throw AiExceptions.badRequest("结论引用了不属于该任务的证据");
        }
        long version = tasks.nextConclusionVersion(taskId);
        UUID id = UUID.randomUUID();
        tasks.insertConclusion(new Conclusion(
                id,
                taskId,
                version,
                normalizedConclusion,
                JsonSupport.write(refs.stream().map(UUID::toString).toList()),
                validation,
                access.requireProject(task.projectId()).actor().id(),
                null));
        appendEvent(
                taskId,
                "CONCLUSION_ADDED",
                Map.of("conclusionId", id.toString(), "version", version, "validationStatus", validation),
                access.requireProject(task.projectId()).actor());
        return tasks.listConclusions(taskId).stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    public List<Conclusion> listConclusions(UUID taskId) {
        require(taskId);
        return tasks.listConclusions(taskId);
    }

    @Transactional
    public Approval requestApproval(
            UUID taskId, String actionType, Map<String, Object> target, OffsetDateTime expiresAt) {
        Task task = requireForWrite(taskId);
        String action = requireText(actionType, "操作类型", 64);
        if (expiresAt == null || !expiresAt.isAfter(OffsetDateTime.now())) {
            throw AiExceptions.badRequest("审批有效期必须晚于当前时间");
        }
        String targetJson = JsonSupport.write(target == null ? Map.of() : target);
        UUID id = UUID.randomUUID();
        tasks.insertApproval(new Approval(
                id,
                taskId,
                action,
                targetJson,
                sha256(action + "\n" + targetJson),
                access.requireProject(task.projectId()).actor().id(),
                expiresAt,
                null,
                "PENDING",
                null));
        appendEvent(
                taskId,
                "APPROVAL_REQUESTED",
                Map.of("approvalId", id.toString(), "actionType", action),
                access.requireProject(task.projectId()).actor());
        return tasks.findApproval(taskId, id);
    }

    public List<Approval> listApprovals(UUID taskId) {
        require(taskId);
        return tasks.listApprovals(taskId);
    }

    @Transactional
    public Approval useApproval(UUID taskId, UUID approvalId) {
        requireForWrite(taskId);
        if (tasks.useApproval(taskId, approvalId) == 0) {
            throw AiExceptions.conflict("APPROVAL_UNAVAILABLE", "审批已使用、已过期或不存在");
        }
        return tasks.findApproval(taskId, approvalId);
    }

    private Task requireForWrite(UUID id) {
        Task task = tasks.findTask(id);
        if (task == null) {
            throw AiExceptions.notFound("调查任务不存在");
        }
        access.requireTask(task.projectId(), task.ownerId(), "investigation:operate");
        return task;
    }

    private void appendEvent(UUID taskId, String type, Map<String, Object> payload, UserIdentity actor) {
        if (tasks.lockTask(taskId) == null) {
            throw AiExceptions.notFound("调查任务不存在");
        }
        tasks.insertEvent(new TaskEvent(
                UUID.randomUUID(),
                taskId,
                tasks.maxEventSeq(taskId) + 1,
                type,
                JsonSupport.write(payload),
                actor.id(),
                null));
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }

    private static String requireText(String value, String field, int max) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw AiExceptions.badRequest(field + "不能为空");
        }
        if (normalized.length() > max) {
            throw AiExceptions.badRequest(field + "不能超过 " + max + " 字符");
        }
        return normalized;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
