package top.yealike.pep.control.runs.delegation;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.yealike.pep.control.access.policy.PolicyService;
import top.yealike.pep.control.agents.domain.AgentRelease;
import top.yealike.pep.control.agents.store.AgentStore;
import top.yealike.pep.control.audit.AiAuditLogMapper;
import top.yealike.pep.control.conversations.ConversationRepository;
import top.yealike.pep.control.integration.OpsIdentityClient;
import top.yealike.pep.control.runs.RunEventService;
import top.yealike.pep.control.runs.RunRepository;
import top.yealike.pep.control.runs.RunStatus;
import top.yealike.pep.control.runs.domain.Run;
import top.yealike.pep.control.runs.mapper.A2aTaskMapper;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.control.support.JsonSupport;
import top.yealike.pep.runtime.contract.DelegateTaskRequest;
import top.yealike.pep.runtime.contract.DelegateTaskResponse;
import top.yealike.pep.runtime.contract.DelegationPolicy;
import top.yealike.pep.runtime.contract.Digests;
import top.yealike.pep.runtime.contract.RunDispatchRequest;
import top.yealike.pep.runtime.contract.RunLineage;

/** 在同一个事务中创建委派任务、子会话与子 Run。 */
@Service
public class A2aTaskService {

    private static final Logger LOG = LoggerFactory.getLogger(A2aTaskService.class);
    private static final int MAX_TASK_CHARS = 4_000;
    private static final int MAX_RESULT_CHARS = 8_000;
    private static final int MAX_RESULT_BYTES = 32_768;
    private static final int LEASE_SECONDS = 30;
    private static final int RECONCILE_BATCH_SIZE = 32;

    private final A2aTaskMapper tasks;
    private final RunRepository runs;
    private final ConversationRepository conversations;
    private final AgentStore agents;
    private final OpsIdentityClient identities;
    private final PolicyService policy;
    private final DelegationAuthorizer authorizer;
    private final TransactionTemplate transactions;
    private final RunEventService events;
    private final AiAuditLogMapper audit;
    private final MeterRegistry metrics;
    private final int maxActiveRoots;

    public A2aTaskService(
            A2aTaskMapper tasks,
            RunRepository runs,
            ConversationRepository conversations,
            AgentStore agents,
            OpsIdentityClient identities,
            PolicyService policy,
            DelegationAuthorizer authorizer,
            PlatformTransactionManager transactionManager,
            RunEventService events,
            AiAuditLogMapper audit,
            MeterRegistry metrics,
            @Value("${ipe.ai.delegation.max-active-roots:2}") int maxActiveRoots) {
        this.tasks = tasks;
        this.runs = runs;
        this.conversations = conversations;
        this.agents = agents;
        this.identities = identities;
        this.policy = policy;
        this.authorizer = authorizer;
        this.transactions = new TransactionTemplate(transactionManager);
        this.events = events;
        this.audit = audit;
        this.metrics = metrics;
        this.maxActiveRoots = Math.max(1, maxActiveRoots);
    }

    /** 校验最新用户身份后，在根 Run 锁内核验预算与幂等性。 */
    public DelegateTaskResponse submit(DelegateTaskRequest request) {
        validate(request);
        Run snapshot = requireRun(request.parentRunId());
        authorizer.requireParent(snapshot, request.executionToken(), request.instanceId());
        try {
            var user = identities.resolveActor(snapshot.actor());
            policy.reload();
            return transactions.execute(ignored -> {
                RunLineage parentLineage = runs.lineage(snapshot.id());
                UUID rootId = parentLineage == null ? snapshot.id() : parentLineage.rootRunId();
                Run root = runs.lock(rootId).orElseThrow(() -> AiExceptions.notFound("根 Run 不存在"));
                Run parent = rootId.equals(snapshot.id())
                        ? root
                        : runs.lock(snapshot.id()).orElseThrow(() -> AiExceptions.notFound("父 Run 不存在"));
                authorizer.requireParent(root, root.executionToken(), request.instanceId());
                authorizer.requireParent(parent, request.executionToken(), request.instanceId());
                if (!Objects.equals(parent.agentReleaseId(), request.sourceReleaseId())) {
                    throw AiExceptions.forbidden("DELEGATE_NOT_ALLOWED", "父 Agent 发布版本不匹配");
                }
                AgentRelease source = release(parent.agentReleaseId());
                var binding = authorizer.binding(source, request.delegateAlias(), user, parent);
                Map<String, Object> payload = payload(request, binding.allowedTools());
                String digest = Digests.digest(payload);
                A2aTask existing = tasks.findByKey(parent.id(), request.idempotencyKey());
                if (existing != null) {
                    if (!existing.requestDigest().equals(digest)) {
                        throw AiExceptions.conflict("IDEMPOTENCY_CONFLICT", "幂等键已用于其他委派请求");
                    }
                    return response(sync(existing.id()), false);
                }
                AgentRelease target = authorizer.target(binding);
                int depth = parentLineage == null ? 1 : parentLineage.depth() + 1;
                var sourcePolicy = source.manifest().delegationPolicy();
                var rootPolicy = root.id().equals(parent.id())
                        ? sourcePolicy
                        : release(root.agentReleaseId()).manifest().delegationPolicy();
                if (sourcePolicy == null
                        || rootPolicy == null
                        || depth > Math.min(sourcePolicy.maxDepth(), rootPolicy.maxDepth())) {
                    throw AiExceptions.unprocessable("DELEGATION_DEPTH_EXCEEDED", "已超过委派深度限制");
                }
                var counts = tasks.counts(parent.id(), binding.alias());
                long tokenLimit = Math.min(sourcePolicy.maxTotalTokens(), rootPolicy.maxTotalTokens());
                if (parentLineage != null) {
                    tokenLimit = Math.min(tokenLimit, parentLineage.maxTotalTokens());
                }
                if (counts.total() >= sourcePolicy.maxCallsPerRun()
                        || counts.matchingAlias() >= binding.maxCalls()
                        || tasks.usedTokens(rootId) >= tokenLimit) {
                    throw AiExceptions.conflict("DELEGATION_BUDGET_EXHAUSTED", "委派预算已耗尽");
                }
                if (counts.active() >= sourcePolicy.maxParallel()) {
                    throw AiExceptions.conflict("DELEGATION_CONCURRENCY_LIMIT", "父 Run 已有活跃委派");
                }
                runs.lockQuota("ai-a2a:active-roots");
                if (tasks.activeRoots(rootId) >= maxActiveRoots) {
                    throw AiExceptions.conflict("DELEGATION_CONCURRENCY_LIMIT", "委派执行并发已满");
                }
                Instant now = Instant.now();
                long timeout = Math.min(
                        Math.min(binding.timeoutMs(), sourcePolicy.maxWallClockMs()),
                        target.manifest().budget().runTimeoutMs());
                Instant deadline = min(parent.deadlineAt(), now.plusMillis(timeout));
                if (!deadline.isAfter(now)) {
                    throw AiExceptions.conflict("DELEGATION_BUDGET_EXHAUSTED", "父 Run 剩余时间不足");
                }
                payload.put("maxTotalTokens", tokenLimit);
                A2aTask task = new A2aTask(
                        UUID.randomUUID(),
                        rootId,
                        parent.id(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        root.conversationId().toString(),
                        source.agentId(),
                        source.id(),
                        target.agentId(),
                        target.id(),
                        binding.alias(),
                        depth,
                        A2aTaskState.SUBMITTED,
                        request.idempotencyKey(),
                        digest,
                        payload,
                        Map.of(),
                        null,
                        null,
                        null,
                        deadline,
                        0,
                        now,
                        null);
                createChild(task, parent, target, question(request));
                publish(task, parent, A2aTaskState.SUBMITTED, 0);
                audit.auditDelegation(
                        parent.actor(), "A2A_TASK_CREATE", "A2A_TASK", task.id().toString(), "SUCCESS", null);
                return response(task, true);
            });
        } catch (AiExceptions.AiException exception) {
            metrics.counter("pep_a2a_delegation_denied_total", "reason", exception.code())
                    .increment();
            audit.auditDelegation(
                    snapshot.actor(), "A2A_TASK_DENIED", "RUN", snapshot.id().toString(), "DENIED", exception.code());
            throw exception;
        }
    }

    /** 在父执行仍有效且 Task 属于该 Run 时返回结果。 */
    public DelegateTaskResponse get(UUID id, UUID parentRunId, long token, String instanceId) {
        requireOwnedTask(id, parentRunId, token, instanceId);
        return transactions.execute(ignored -> response(sync(id), false));
    }

    /** 由仍持有父 Run 的 Runtime 请求取消指定子任务。 */
    public DelegateTaskResponse cancel(UUID id, UUID parentRunId, long token, String instanceId) {
        A2aTask task = requireOwnedTask(id, parentRunId, token, instanceId);
        runs.requestCancel(task.childRunId());
        return transactions.execute(ignored -> response(sync(id), false));
    }

    private A2aTask requireOwnedTask(UUID id, UUID parentRunId, long token, String instanceId) {
        authorizer.requireParent(requireRun(parentRunId), token, instanceId);
        A2aTask task = tasks.find(id);
        if (task == null || !parentRunId.equals(task.parentRunId())) {
            throw AiExceptions.notFound("委派任务不存在");
        }
        return task;
    }

    /** 从子 Run 的持久态收敛 Task，包括由 Run reaper 终结的任务。 */
    @Scheduled(
            fixedDelayString = "${ipe.ai.delegation.reconcile-delay-ms:1000}",
            initialDelayString = "${ipe.ai.delegation.reconcile-delay-ms:1000}")
    public void reconcile() {
        tasks.orphanRuns(RECONCILE_BATCH_SIZE).forEach(runs::requestCancel);
        for (UUID id : tasks.pendingSync(RECONCILE_BATCH_SIZE)) {
            try {
                transactions.executeWithoutResult(ignored -> sync(id));
            } catch (RuntimeException exception) {
                LOG.warn("委派状态同步失败 taskId={}", id, exception);
            }
        }
    }

    private void createChild(A2aTask task, Run parent, AgentRelease target, String question) {
        UUID messageId = UUID.randomUUID();
        tasks.insertTask(task, JsonSupport.write(task.request()));
        int conversation = tasks.insertChildConversation(
                task,
                parent.conversationId(),
                target.manifest().displayName(),
                target.agentKey(),
                target.version(),
                target.manifestDigest());
        int run = tasks.insertChildRun(
                task,
                messageId,
                target.agentKey(),
                target.version(),
                target.manifestDigest(),
                target.manifest().model().profileKey(),
                question,
                min(task.deadlineAt(), task.createdAt().plusSeconds(LEASE_SECONDS)));
        if (conversation != 1 || run != 1) {
            throw AiExceptions.conflict("CHILD_RUN_FAILED", "无法创建子 Run");
        }
        long seq = conversations.nextMessageSeq(task.childConversationId());
        conversations.insertMessage(
                messageId,
                task.childConversationId(),
                task.childRunId(),
                "user",
                question,
                List.of(),
                "FINAL",
                seq,
                task.createdAt());
    }

    private A2aTask sync(UUID id) {
        A2aTask task = tasks.lock(id);
        if (task == null || task.state().terminal()) {
            return task;
        }
        Run child = requireRun(task.childRunId());
        if (!child.terminal()
                && child.deadlineAt() != null
                && child.deadlineAt().isBefore(Instant.now())) {
            String status = runs.terminateExpired(
                    child.id(), child.executionToken(), child.runtimeInstanceId(), "RUN_TIMEOUT", "子任务已超过执行截止时间");
            if (status != null) {
                boolean cancelled = RunStatus.CANCELLED.equals(status);
                events.publish(
                        child.conversationId(),
                        child.id(),
                        cancelled ? "run.cancelled" : "run.failed",
                        cancelled
                                ? Map.of("status", status)
                                : Map.of("status", status, "errorCode", "RUN_TIMEOUT", "message", "子任务执行超时"));
                child = requireRun(task.childRunId());
            }
        }
        A2aTaskState next = switch (child.status()) {
            case RunStatus.COMPLETED -> A2aTaskState.COMPLETED;
            case RunStatus.FAILED -> A2aTaskState.FAILED;
            case RunStatus.CANCELLED -> A2aTaskState.CANCELED;
            case RunStatus.CANCEL_REQUESTED -> A2aTaskState.CANCEL_REQUESTED;
            case RunStatus.RUNNING -> A2aTaskState.WORKING;
            default -> A2aTaskState.SUBMITTED;
        };
        if (next == task.state() || (task.state() == A2aTaskState.CANCEL_REQUESTED && !next.terminal())) {
            return task;
        }
        if (task.state() == A2aTaskState.SUBMITTED && next.terminal() && child.lastHeartbeatAt() != null) {
            if (tasks.transition(
                            new A2aTaskTransition(id, task.version(), A2aTaskState.WORKING, "{}", "{}", null, null))
                    == 1) {
                publish(task, requireRun(task.parentRunId()), A2aTaskState.WORKING, task.version() + 1);
                task = tasks.find(id);
            }
        }
        Map<String, Object> result = next == A2aTaskState.COMPLETED ? result(task) : Map.of();
        String error = switch (next) {
            case CANCELED -> "CHILD_RUN_CANCELED";
            case FAILED ->
                switch (Objects.toString(child.errorCode(), "")) {
                    case "RUN_TIMED_OUT", "TIMED_OUT", "RUN_TIMEOUT" -> "CHILD_RUN_TIMEOUT";
                    case "DELEGATION_BUDGET_EXHAUSTED" -> child.errorCode();
                    default -> "CHILD_RUN_FAILED";
                };
            default -> null;
        };
        String message = switch (Objects.toString(error, "")) {
            case "CHILD_RUN_CANCELED" -> "子任务已取消";
            case "CHILD_RUN_TIMEOUT" -> "子任务已超过执行截止时间";
            case "DELEGATION_BUDGET_EXHAUSTED" -> "委派 Token 预算已耗尽";
            case "CHILD_RUN_FAILED" -> "子任务执行失败";
            default -> null;
        };
        if (tasks.transition(new A2aTaskTransition(
                        id,
                        task.version(),
                        next,
                        JsonSupport.write(result),
                        JsonSupport.write(child.usage() == null ? Map.of() : child.usage()),
                        error,
                        message))
                == 1) {
            Run parent = requireRun(task.parentRunId());
            publish(task, parent, next, task.version() + 1);
            if (next == A2aTaskState.CANCELED || next == A2aTaskState.CANCEL_REQUESTED) {
                audit.auditDelegation(
                        parent.actor(), "A2A_TASK_CANCEL", "A2A_TASK", id.toString(), "SUCCESS", next.name());
            }
        }
        return tasks.find(id);
    }

    private Map<String, Object> result(A2aTask task) {
        String answer = conversations.listMessages(task.childConversationId(), 2).stream()
                .filter(message -> "assistant".equals(message.role()))
                .map(message -> message.content())
                .findFirst()
                .orElse("");
        int maxChars = task.request().get("maxChars") instanceof Number value ? value.intValue() : MAX_RESULT_CHARS;
        int end = answer.offsetByCodePoints(0, Math.min(maxChars, answer.codePointCount(0, answer.length())));
        String summary = answer.substring(0, end);
        while (JsonSupport.write(Map.of("summary", summary, "truncated", true))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        .length
                > MAX_RESULT_BYTES) {
            int shortened = summary.offsetByCodePoints(0, summary.codePointCount(0, summary.length()) / 2);
            summary = summary.substring(0, shortened);
        }
        return Map.of("summary", summary, "truncated", summary.length() < answer.length());
    }

    private void publish(A2aTask task, Run parent, A2aTaskState next, long version) {
        tasks.insertEvent(task.id(), version + 1, "task." + next.name().toLowerCase(Locale.ROOT));
        String type = switch (next) {
            case SUBMITTED -> "delegate.queued";
            case WORKING -> "delegate.started";
            case CANCEL_REQUESTED -> "delegate.cancel_requested";
            case COMPLETED -> "delegate.completed";
            case CANCELED -> "delegate.cancelled";
            default -> "delegate.failed";
        };
        events.publish(
                parent.conversationId(),
                parent.id(),
                type,
                Map.of("taskId", task.id().toString(), "state", next.name(), "version", version));
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        metrics.counter("pep_a2a_task_total", "state", next.name())
                                .increment();
                        if (next.terminal()) {
                            metrics.timer("pep_a2a_task_duration", "state", next.name())
                                    .record(java.time.Duration.between(task.createdAt(), Instant.now()));
                        }
                    }
                });
    }

    private DelegateTaskResponse response(A2aTask task, boolean created) {
        Run child = requireRun(task.childRunId());
        RunDispatchRequest dispatch = new RunDispatchRequest(
                child.id(),
                child.agentReleaseId(),
                child.manifestDigest(),
                child.executionToken(),
                child.agentKey(),
                child.agentVersion(),
                child.conversationId(),
                child.actor(),
                child.actorAuthVersion() == null ? 0 : child.actorAuthVersion(),
                child.deadlineAt(),
                null,
                child.traceContext());
        RunLineage lineage = runs.lineage(child.id());
        return new DelegateTaskResponse(
                task.id(),
                task.state().name(),
                created,
                dispatch,
                lineage.allowedTools(),
                (String) task.result().get("summary"),
                Boolean.TRUE.equals(task.result().get("truncated")),
                task.usage(),
                task.errorCode(),
                task.errorMessage());
    }

    private Run requireRun(UUID id) {
        return runs.find(id).orElseThrow(() -> AiExceptions.notFound("Run 不存在"));
    }

    private AgentRelease release(UUID id) {
        return agents.findRelease(id).orElseThrow(() -> AiExceptions.notFound("发布版本不存在"));
    }

    private static Map<String, Object> payload(DelegateTaskRequest request, List<String> allowedTools) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", request.delegateAlias());
        result.put("task", request.task().strip());
        result.put("contextRefs", List.of());
        result.put("allowedTools", allowedTools);
        if (request.expectedOutput() != null) {
            result.put("format", request.expectedOutput().format());
            result.put("maxChars", request.expectedOutput().maxChars());
        }
        if (request.deadlineSeconds() != null) {
            result.put("deadlineSeconds", request.deadlineSeconds());
        }
        return result;
    }

    private static String question(DelegateTaskRequest request) {
        var expected = request.expectedOutput();
        if (expected == null || expected.format() == null) {
            return request.task().strip();
        }
        return request.task().strip() + "\n\n期望输出格式：" + expected.format();
    }

    private static void validate(DelegateTaskRequest request) {
        if (request == null
                || request.parentRunId() == null
                || request.sourceReleaseId() == null
                || request.delegateAlias() == null
                || !request.delegateAlias().matches("[a-z][a-z0-9_]{1,39}")
                || request.idempotencyKey() == null
                || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 128
                || request.task() == null
                || request.task().isBlank()
                || request.task().length() > MAX_TASK_CHARS) {
            throw AiExceptions.badRequest("委派请求缺少必填字段或超长");
        }
        if (request.contextRefs() != null && !request.contextRefs().isEmpty()) {
            throw AiExceptions.unprocessable("CONTEXT_REFS_UNSUPPORTED", "请在子任务说明中填写必要信息");
        }
        if (request.deadlineSeconds() != null
                && (request.deadlineSeconds() < 1
                        || request.deadlineSeconds() > DelegationPolicy.HARD_MAX_TIME_MS / 1000)) {
            throw AiExceptions.badRequest("子任务等待时间超出范围");
        }
        var expected = request.expectedOutput();
        if (expected != null
                && ((expected.maxChars() != null && (expected.maxChars() < 1 || expected.maxChars() > MAX_RESULT_CHARS))
                        || (expected.format() != null
                                && !List.of("text", "markdown", "json").contains(expected.format())))) {
            throw AiExceptions.badRequest("期望输出格式或长度不受支持");
        }
    }

    private static Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }
}
