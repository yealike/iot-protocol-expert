package top.yealike.ipe.ai.run;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.ipe.ai.agent.AgentCatalog;
import top.yealike.ipe.ai.conversation.ConversationRecords.Conversation;
import top.yealike.ipe.ai.conversation.ConversationRepository;
import top.yealike.ipe.ai.identity.UserIdentity;
import top.yealike.ipe.ai.policy.PolicyService;
import top.yealike.ipe.ai.run.RunRecords.Run;
import top.yealike.ipe.ai.run.RunRecords.RunView;
import top.yealike.ipe.ai.support.AiExceptions;

/** Run 生命周期：提交、暂停、恢复、取消。提交与消息落库在同一事务，网络重试由幂等键去重。 */
@Service
public class RunService {

    private static final int MAX_QUESTION_LENGTH = 4000;

    private final ConversationRepository conversations;
    private final RunRepository runs;
    private final RunEventService events;
    private final PolicyService policy;
    private final AgentCatalog catalog;
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    public RunService(
            ConversationRepository conversations,
            RunRepository runs,
            RunEventService events,
            PolicyService policy,
            AgentCatalog catalog,
            io.micrometer.core.instrument.MeterRegistry metrics) {
        this.conversations = conversations;
        this.runs = runs;
        this.events = events;
        this.policy = policy;
        this.catalog = catalog;
        this.metrics = metrics;
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
        var existing = runs.findByIdempotency(conversationId, key);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        long seq = conversations.nextMessageSeq(conversationId);
        // 分配消息序号时持有会话行锁，重试请求须在锁内再次检查幂等键。
        existing = runs.findByIdempotency(conversationId, key);
        if (existing.isPresent()) return existing.get();
        Instant now = Instant.now();
        conversations.insertMessage(messageId, conversationId, runId, "user", question, List.of(), "FINAL", seq, now);

        AgentCatalog.Definition definition = catalog.find(conversation.agentKey());
        if (definition == null) {
            throw AiExceptions.notFound("Agent 未发布：" + conversation.agentKey());
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
                definition.modelKey(),
                key,
                policy.version(),
                priority != null && priority > 100 ? 200 : 100,
                now);

        metrics.counter("ai_runs_created_total", "agent", conversation.agentKey())
                .increment();
        Map<String, Object> messagePayload = new LinkedHashMap<>();
        messagePayload.put("messageId", messageId.toString());
        messagePayload.put("seq", seq);
        messagePayload.put("content", question);
        events.publish(conversationId, runId, "message.user", messagePayload);
        events.publish(
                conversationId,
                runId,
                "run.queued",
                Map.of("runId", runId.toString(), "priority", priority != null && priority > 100 ? 200 : 100));
        if (priority != null && priority > 100) {
            runs.current(conversationId)
                    .filter(active -> RunRecords.RUNNING.equals(active.status()))
                    .ifPresent(active -> {
                        if (runs.requestPause(active.id())) {
                            events.publish(
                                    conversationId,
                                    active.id(),
                                    "run.pause_requested",
                                    Map.of("status", RunRecords.PAUSE_REQUESTED, "reason", "PRIORITY_RUN"));
                        }
                    });
        }
        return runs.find(runId).orElseThrow(() -> AiExceptions.unavailable("Run 创建失败"));
    }

    public void pause(UserIdentity identity, UUID runId) {
        Run run = requireRun(identity, runId);
        if (!RunRecords.RUNNING.equals(run.status())) {
            throw AiExceptions.conflict("RUN_NOT_RUNNING", "只有执行中的 Run 可以暂停");
        }
        if (!runs.requestPause(runId)) {
            throw AiExceptions.conflict("RUN_NOT_RUNNING", "Run 状态已变化，请刷新后重试");
        }
        events.publish(
                run.conversationId(), runId, "run.pause_requested", Map.of("status", RunRecords.PAUSE_REQUESTED));
    }

    public void resume(UserIdentity identity, UUID runId) {
        Run run = requireRun(identity, runId);
        if (!RunRecords.PAUSED.equals(run.status())) {
            throw AiExceptions.conflict("RUN_NOT_PAUSED", "只有已暂停的 Run 可以继续");
        }
        PolicyService.Decision decision = policy.canUseAgent(identity, run.agentKey());
        if (!decision.allowed()) {
            throw AiExceptions.forbidden("AGENT_DENIED", "权限已变化：" + decision.reason());
        }
        if (!runs.requeuePaused(runId)) {
            throw AiExceptions.conflict("RUN_NOT_PAUSED", "Run 状态已变化，请刷新后重试");
        }
        events.publish(run.conversationId(), runId, "run.queued", Map.of("runId", runId.toString(), "resumed", true));
    }

    public String cancel(UserIdentity identity, UUID runId) {
        Run run = requireRun(identity, runId);
        if (run.terminal()) {
            return run.status();
        }
        runs.requestCancel(runId);
        String status = runs.find(runId).orElseThrow().status();
        if (RunRecords.COMPLETED.equals(status) || RunRecords.FAILED.equals(status)) return status;
        events.publish(
                run.conversationId(),
                runId,
                RunRecords.CANCELLED.equals(status) ? "run.cancelled" : "run.cancel_requested",
                Map.of("status", status, "note", "已发出的外部请求不保证远端未执行"));
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
}
