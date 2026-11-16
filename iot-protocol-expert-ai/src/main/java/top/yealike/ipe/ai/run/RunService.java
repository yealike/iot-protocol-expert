package top.yealike.ipe.ai.run;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.ipe.ai.conversation.ConversationRecords.Conversation;
import top.yealike.ipe.ai.conversation.ConversationRepository;
import top.yealike.ipe.ai.identity.UserIdentity;
import top.yealike.ipe.ai.observability.AiObservability;
import top.yealike.ipe.ai.platform.agent.AgentService;
import top.yealike.ipe.ai.platform.config.PlatformProperties;
import top.yealike.ipe.ai.platform.dispatch.RuntimeDispatchClient;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.AgentRelease;
import top.yealike.ipe.ai.policy.PolicyService;
import top.yealike.ipe.ai.run.RunRecords.Run;
import top.yealike.ipe.ai.run.RunRecords.RunView;
import top.yealike.ipe.ai.support.AiExceptions;

/** Run 生命周期：提交与取消。提交与消息落库在同一事务，网络重试由幂等键去重。 */
@Service
public class RunService {

    private static final int MAX_QUESTION_LENGTH = 4000;

    private final ConversationRepository conversations;
    private final RunRepository runs;
    private final RunEventService events;
    private final PolicyService policy;
    private final AgentService agents;
    private final RuntimeDispatchClient runtime;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    private final AiObservability observability;
    private final PlatformProperties platform;

    public RunService(
            ConversationRepository conversations,
            RunRepository runs,
            RunEventService events,
            PolicyService policy,
            AgentService agents,
            RuntimeDispatchClient runtime,
            io.micrometer.core.instrument.MeterRegistry metrics,
            AiObservability observability,
            PlatformProperties platform) {
        this.conversations = conversations;
        this.runs = runs;
        this.events = events;
        this.policy = policy;
        this.agents = agents;
        this.runtime = runtime;
        this.metrics = metrics;
        this.observability = observability;
        this.platform = platform;
    }

    @Transactional
    public Run submit(
            UserIdentity identity, UUID conversationId, String question, String idempotencyKey, Integer priority) {
        if (question == null || question.isBlank()) {
            throw AiExceptions.badRequest("问题不能为空");
        }
        if (question.length() > MAX_QUESTION_LENGTH) {
            throw AiExceptions.badRequest("问题过长，请控制在 " + MAX_QUESTION_LENGTH + " 字以内");
        }
        Conversation conversation = requireConversation(identity, conversationId);
        PolicyService.Decision decision = policy.canUseAgent(identity, conversation.agentKey());
        if (!decision.allowed()) {
            throw AiExceptions.forbidden("AGENT_DENIED", decision.reason());
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? UUID.randomUUID().toString()
                : idempotencyKey.strip();
        if (key.length() > 120) {
            throw AiExceptions.badRequest("幂等键过长");
        }
        if (priority != null && priority != 100) {
            throw AiExceptions.badRequest("当前一期不支持优先级控制，priority 仅允许 100");
        }
        var existing = observability.observeSupplier(
                "ai run idempotency lookup", () -> runs.findByIdempotency(conversationId, key));
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        long seq = conversations.nextMessageSeq(conversationId);
        // 分配消息序号时持有会话行锁，重试请求须在锁内再次检查幂等键。
        existing = observability.observeSupplier(
                "ai run idempotency lookup", () -> runs.findByIdempotency(conversationId, key));
        if (existing.isPresent()) return existing.get();
        runs.lockQuota("ai-run-quota:global");
        runs.lockQuota("ai-run-quota:user:" + identity.actor());
        // 配额锁会串行化同用户并发提交；拿到锁后再次检查，已提交的重试直接返回原 Run。
        existing = observability.observeSupplier(
                "ai run idempotency lookup", () -> runs.findByIdempotency(conversationId, key));
        if (existing.isPresent()) return existing.get();
        enforceQuota(identity, conversationId);
        Instant now = Instant.now();
        conversations.insertMessage(messageId, conversationId, runId, "user", question, List.of(), "FINAL", seq, now);

        AgentRelease release = conversation.agentReleaseId() == null
                ? agents.requirePublishedRelease(conversation.agentKey(), conversation.agentVersion())
                : agents.release(conversation.agentReleaseId());
        if (!"ACTIVE".equals(release.state())) {
            throw AiExceptions.conflict("RELEASE_NOT_ACTIVE", "会话绑定的 Agent 版本不可用");
        }
        runs.insertRun(
                runId,
                conversationId,
                messageId,
                identity.actor(),
                identity.roles(),
                identity.authVersion(),
                question,
                conversation.agentKey(),
                conversation.agentVersion(),
                release.id(),
                release.manifestDigest(),
                release.manifest().model() == null
                        ? null
                        : release.manifest().model().profileKey(),
                key,
                policy.version(),
                observability.traceparent(),
                100,
                now);

        metrics.counter("ai_runs_created_total", "agent", conversation.agentKey())
                .increment();
        Map<String, Object> messagePayload = new LinkedHashMap<>();
        messagePayload.put("messageId", messageId.toString());
        messagePayload.put("seq", seq);
        messagePayload.put("content", question);
        events.publish(conversationId, runId, "message.user", messagePayload);
        events.publish(conversationId, runId, "run.queued", Map.of("runId", runId.toString(), "priority", 100));
        return runs.find(runId).orElseThrow(() -> AiExceptions.unavailable("Run 创建失败"));
    }

    public String cancel(UserIdentity identity, UUID runId) {
        Run run = requireRun(identity, runId);
        if (run.terminal()) {
            return run.status();
        }
        runs.requestCancel(runId);
        Run current = runs.find(runId).orElseThrow();
        String status = current.status();
        if (RunRecords.COMPLETED.equals(status) || RunRecords.FAILED.equals(status)) return status;
        if (RunRecords.CANCELLED.equals(status)) {
            events.publish(
                    run.conversationId(), runId, "run.cancelled", Map.of("status", status, "reason", "USER_CANCELLED"));
            return status;
        }
        if (RunRecords.CANCEL_REQUESTED.equals(status)) {
            RuntimeDispatchClient.RuntimeTarget target = runtimeTarget(current);
            if (target != null && current.executionToken() > 0) {
                runtime.cancel(target, runId, current.executionToken());
            }
            events.publish(
                    run.conversationId(),
                    runId,
                    "run.cancel_requested",
                    Map.of("status", status, "note", "已通知执行实例停止；外部工具调用不保证物理未发生"));
        }
        return status;
    }

    public RunView view(UserIdentity identity, UUID runId) {
        return toView(requireRun(identity, runId));
    }

    public RunView toView(Run run) {
        return new RunView(
                run.id(),
                run.conversationId(),
                run.question(),
                run.agentKey(),
                run.agentVersion(),
                run.manifestDigest(),
                run.traceContext(),
                run.status(),
                run.priority(),
                run.errorCode(),
                run.errorMessage(),
                run.startedAt(),
                run.finishedAt(),
                run.createdAt(),
                runs.listToolCalls(run.id()));
    }

    private Conversation requireConversation(UserIdentity identity, UUID conversationId) {
        return conversations
                .find(conversationId)
                .filter(conversation -> conversation.userId().equals(identity.actor()))
                .orElseThrow(() -> AiExceptions.notFound("会话不存在"));
    }

    private void enforceQuota(UserIdentity identity, UUID conversationId) {
        PlatformProperties.Runtime quota = platform.runtime();
        int globalQueued = runs.countQueued();
        if (globalQueued >= quota.queueLimit()) {
            throw new AiExceptions.AiException(429, "RUN_QUEUE_FULL", "全局排队任务已达上限，请稍后重试");
        }
        int userQueued = runs.countUserQueued(identity.actor(), null);
        if (userQueued >= quota.perUserQueued()) {
            throw new AiExceptions.AiException(429, "USER_QUEUE_FULL", "当前账号排队任务已达上限，请稍后重试");
        }
        int userInFlight = runs.countUserInFlight(identity.actor(), null);
        if (userInFlight >= quota.perUserInFlight()) {
            throw new AiExceptions.AiException(429, "USER_RUN_BUSY", "当前账号已有任务在途，请等待完成后再提交");
        }
    }

    public Run requireRun(UserIdentity identity, UUID runId) {
        Run run = runs.find(runId).orElseThrow(() -> AiExceptions.notFound("Run 不存在"));
        Conversation conversation =
                conversations.find(run.conversationId()).orElseThrow(() -> AiExceptions.notFound("会话不存在"));
        if (!conversation.userId().equals(identity.actor())) {
            throw AiExceptions.notFound("Run 不存在");
        }
        policy.reload();
        if (!policy.canUseAgent(identity, run.agentKey()).allowed()) {
            throw AiExceptions.forbidden("AGENT_DENIED", "当前账号无权访问该任务使用的 Agent");
        }
        return run;
    }

    public List<Run> history(UUID conversationId, int limit) {
        return runs.listByConversation(conversationId, Math.min(Math.max(limit, 1), 100));
    }

    private RuntimeDispatchClient.RuntimeTarget runtimeTarget(Run run) {
        var targets = runtime.agentTargets(run.agentKey(), run.agentVersion());
        String instanceId = run.runtimeInstanceId();
        if (instanceId == null || instanceId.isBlank()) {
            return targets.isEmpty() ? null : targets.getFirst();
        }
        return targets.stream()
                .filter(item -> instanceId.equals(item.instanceId()))
                .findFirst()
                .orElse(null);
    }
}
