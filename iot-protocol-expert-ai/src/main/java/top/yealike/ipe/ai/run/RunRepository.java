package top.yealike.ipe.ai.run;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import top.yealike.ipe.ai.mapper.RunMapper;
import top.yealike.ipe.ai.run.RunRecords.Run;
import top.yealike.ipe.ai.run.RunRecords.RunEvent;
import top.yealike.ipe.ai.run.RunRecords.ToolCall;
import top.yealike.ipe.ai.support.JsonSupport;

/** Run / 事件 / 工具调用的 SQL 访问层。 */
@Repository
public class RunRepository {

    private final RunMapper mapper;

    public RunRepository(RunMapper mapper) {
        this.mapper = mapper;
    }

    public void insertRun(
            UUID id,
            UUID conversationId,
            UUID userMessageId,
            String actor,
            List<String> actorRoles,
            long actorAuthVersion,
            String question,
            String agentKey,
            String agentVersion,
            String modelKey,
            String idempotencyKey,
            String policyVersion,
            String traceContext,
            int priority,
            Instant createdAt) {
        mapper.insertRun(
                id,
                conversationId,
                userMessageId,
                actor,
                JsonSupport.write(actorRoles == null ? List.of() : actorRoles),
                actorAuthVersion,
                question,
                agentKey,
                agentVersion,
                modelKey,
                priority,
                idempotencyKey,
                policyVersion,
                traceContext,
                createdAt);
    }

    public Optional<Run> find(UUID id) {
        return mapper.find(id).stream().findFirst();
    }

    public Optional<Run> findByIdempotency(UUID conversationId, String idempotencyKey) {
        return mapper.findByIdempotency(conversationId, idempotencyKey).stream().findFirst();
    }

    public Optional<Run> latest(UUID conversationId) {
        return mapper.latest(conversationId).stream().findFirst();
    }

    public Optional<Run> current(UUID conversationId) {
        return mapper.current(conversationId).stream().findFirst();
    }

    public List<Run> listByConversation(UUID conversationId, int limit) {
        return mapper.listByConversation(conversationId, limit);
    }

    /**
     * 选出下一个可执行的 Run：排除仍持有执行权的会话，再原子认领（status 条件是并发保护）。
     *
     * <p>按优先级与提交顺序取队首；认领失败直接返回空，等下一轮调度。
     */
    public Optional<Run> claimNextQueued(String leaseOwner, int leaseSeconds) {
        List<UUID> candidates = mapper.selectNextQueued();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        UUID id = candidates.getFirst();
        int updated = mapper.claimQueued(leaseOwner, leaseSeconds, id);
        if (updated == 0) {
            return Optional.empty();
        }
        return find(id);
    }

    public void renewLease(UUID id, String leaseOwner, int leaseSeconds) {
        mapper.renewLease(leaseSeconds, id, leaseOwner);
    }

    public boolean transition(UUID id, String expected, String next) {
        return mapper.transition(next, id, expected) > 0;
    }

    public boolean requestPause(UUID id) {
        return mapper.requestPause(id) > 0;
    }

    public boolean markCompleted(UUID id, Map<String, Object> usage) {
        return mapper.markCompleted(JsonSupport.write(usage), id) > 0;
    }

    public boolean markFailed(UUID id, String code, String message, Map<String, Object> usage) {
        return mapper.markFailed(code, message, JsonSupport.write(usage), id) > 0;
    }

    public void markCancelled(UUID id, Map<String, Object> usage) {
        mapper.markCancelled(JsonSupport.write(usage), id);
    }

    public boolean markPaused(UUID id, Map<String, Object> usage) {
        return mapper.markPaused(JsonSupport.write(usage), id) > 0;
    }

    /** 恢复：PAUSED → QUEUED，保留原 queue_seq，使其排在恢复之后提交的新问题之前。 */
    public boolean requeuePaused(UUID id) {
        return mapper.requeuePaused(id) > 0;
    }

    public void requestCancel(UUID id) {
        mapper.requestCancel(id);
    }

    public void cancelQueued(UUID id) {
        mapper.cancelQueued(id);
    }

    public int countRunning() {
        Integer count = mapper.countRunning();
        return count == null ? 0 : count;
    }

    public boolean isCancellationRequested(UUID id) {
        String status = mapper.selectStatus(id).stream().findFirst().orElse(null);
        return RunRecords.CANCEL_REQUESTED.equals(status) || RunRecords.CANCELLED.equals(status);
    }

    public boolean isPauseRequested(UUID id) {
        String status = mapper.selectStatus(id).stream().findFirst().orElse(null);
        return RunRecords.PAUSE_REQUESTED.equals(status);
    }

    // ---------------------------------------------------------------- events

    public long nextEventCursor(UUID conversationId) {
        Long cursor = mapper.nextEventCursor(conversationId);
        if (cursor == null) {
            throw new IllegalStateException("conversation not found: " + conversationId);
        }
        return cursor;
    }

    public void insertEvent(
            UUID conversationId, UUID runId, long cursor, String type, Map<String, Object> payload, Instant createdAt) {
        mapper.insertEvent(conversationId, runId, cursor, type, JsonSupport.write(payload), createdAt);
    }

    public List<RunEvent> eventsAfter(UUID conversationId, long cursor, int limit) {
        return mapper.eventsAfter(conversationId, cursor, limit);
    }

    public long latestCursor(UUID conversationId) {
        Long cursor = mapper.latestCursor(conversationId);
        return cursor == null ? 0 : cursor;
    }

    // ------------------------------------------------------------ tool calls

    public void insertToolCall(
            UUID id,
            UUID runId,
            UUID conversationId,
            String toolName,
            String decision,
            String denyReason,
            Map<String, Object> argsSummary,
            Instant createdAt) {
        mapper.insertToolCall(
                id,
                runId,
                conversationId,
                toolName,
                decision.equals("ALLOW") ? "SENT" : "DENIED",
                decision,
                denyReason,
                JsonSupport.write(argsSummary == null ? Map.of() : argsSummary),
                createdAt);
    }

    public void finishToolCall(UUID id, String status, Map<String, Object> resultSummary, int durationMs) {
        mapper.finishToolCall(
                status, JsonSupport.write(resultSummary == null ? Map.of() : resultSummary), durationMs, id);
    }

    public List<ToolCall> listToolCalls(UUID runId) {
        return mapper.listToolCalls(runId);
    }

    public Optional<RunEvent> eventByCursor(UUID conversationId, long cursor) {
        return mapper.eventByCursor(conversationId, cursor).stream().findFirst();
    }
}
