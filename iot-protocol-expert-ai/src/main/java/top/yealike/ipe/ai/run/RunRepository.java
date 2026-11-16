package top.yealike.ipe.ai.run;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import top.yealike.ipe.ai.mapper.RunMapper;
import top.yealike.ipe.ai.run.RunRecords.Run;
import top.yealike.ipe.ai.run.RunRecords.RunEvent;
import top.yealike.ipe.ai.run.RunRecords.RuntimeEventInsert;
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
            UUID agentReleaseId,
            String manifestDigest,
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
                agentReleaseId,
                manifestDigest,
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

    public Optional<Run> lock(UUID id) {
        return mapper.lockRun(id).stream().findFirst();
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
    public Optional<Run> claimNextForDispatch(String dispatchOwner, int leaseSeconds, Instant deadlineAt) {
        List<UUID> candidates = mapper.selectNextQueued();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        UUID id = candidates.getFirst();
        int updated = mapper.claimForDispatch(dispatchOwner, leaseSeconds, deadlineAt, id);
        if (updated == 0) {
            return Optional.empty();
        }
        return find(id);
    }

    public boolean markRunning(UUID id, long executionToken, String instanceId, int leaseSeconds) {
        return mapper.markRunning(id, executionToken, instanceId, leaseSeconds) > 0;
    }

    public boolean bindDispatchTarget(UUID id, long executionToken, String instanceId, int leaseSeconds) {
        return mapper.bindDispatchTarget(id, executionToken, instanceId, leaseSeconds) > 0;
    }

    public boolean deferDispatch(UUID id, long executionToken, Instant nextAttemptAt, int leaseSeconds) {
        return mapper.deferDispatch(id, executionToken, nextAttemptAt, leaseSeconds) > 0;
    }

    public boolean releaseDispatch(UUID id, long executionToken, Instant nextAttemptAt) {
        return mapper.releaseDispatch(id, executionToken, nextAttemptAt) > 0;
    }

    public boolean heartbeat(UUID id, long executionToken, String instanceId, int leaseSeconds) {
        return mapper.heartbeat(id, executionToken, instanceId, leaseSeconds) > 0;
    }

    public void renewLease(UUID id, String leaseOwner, int leaseSeconds) {
        mapper.renewLease(leaseSeconds, id, leaseOwner);
    }

    public boolean transition(UUID id, String expected, String next) {
        return mapper.transition(next, id, expected) > 0;
    }

    public boolean markCompleted(UUID id, Map<String, Object> usage) {
        return mapper.markCompleted(JsonSupport.write(usage), id) > 0;
    }

    public boolean markCompleted(UUID id, long executionToken, String instanceId, Map<String, Object> usage) {
        return mapper.markCompletedWithToken(JsonSupport.write(usage), id, executionToken, instanceId) > 0;
    }

    public boolean markFailed(UUID id, String code, String message, Map<String, Object> usage) {
        return mapper.markFailed(code, message, JsonSupport.write(usage), id) > 0;
    }

    public boolean markFailed(
            UUID id, long executionToken, String instanceId, String code, String message, Map<String, Object> usage) {
        return mapper.markFailedWithToken(code, message, JsonSupport.write(usage), id, executionToken, instanceId) > 0;
    }

    public void markCancelled(UUID id, Map<String, Object> usage) {
        mapper.markCancelled(JsonSupport.write(usage), id);
    }

    public boolean markCancelled(UUID id, long executionToken, String instanceId, Map<String, Object> usage) {
        return mapper.markCancelledWithToken(JsonSupport.write(usage), id, executionToken, instanceId) > 0;
    }

    public List<Run> expiredLeases(int limit) {
        return mapper.selectExpiredLeases(limit);
    }

    public List<Run> dispatchRetries(int limit) {
        return mapper.selectDispatchRetries(limit);
    }

    public List<Run> cancelPending(int seconds, int limit) {
        return mapper.selectCancelPending(seconds, limit);
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

    public int countQueued() {
        Integer count = mapper.countQueued();
        return count == null ? 0 : count;
    }

    public int countUserQueued(String actor, UUID excludeConversationId) {
        Integer count = mapper.countUserQueued(actor, excludeConversationId);
        return count == null ? 0 : count;
    }

    public int countUserInFlight(String actor, UUID excludeConversationId) {
        Integer count = mapper.countUserInFlight(actor, excludeConversationId);
        return count == null ? 0 : count;
    }

    public void lockQuota(String scope) {
        mapper.lockQuota(scope);
    }

    public boolean isCancellationRequested(UUID id) {
        String status = mapper.selectStatus(id).stream().findFirst().orElse(null);
        return RunRecords.CANCEL_REQUESTED.equals(status) || RunRecords.CANCELLED.equals(status);
    }

    public boolean lockRuntimeEventRun(UUID id, long executionToken, String instanceId) {
        return mapper.lockRuntimeEventRun(id, executionToken, instanceId) != null;
    }

    public List<RunRecords.RuntimeEventFingerprint> runtimeEventFingerprints(UUID runId, List<Long> sourceSeqs) {
        return mapper.runtimeEventFingerprints(runId, sourceSeqs);
    }

    // ---------------------------------------------------------------- events

    public long nextEventCursor(UUID conversationId) {
        Long cursor = mapper.nextEventCursor(conversationId);
        if (cursor == null) {
            throw new IllegalStateException("conversation not found: " + conversationId);
        }
        return cursor;
    }

    public long reserveEventCursors(UUID conversationId, int count) {
        Long cursor = mapper.reserveEventCursors(conversationId, count);
        if (cursor == null) {
            throw new IllegalStateException("conversation not found: " + conversationId);
        }
        return cursor;
    }

    public void insertEvent(
            UUID conversationId, UUID runId, long cursor, String type, Map<String, Object> payload, Instant createdAt) {
        mapper.insertEvent(conversationId, runId, cursor, type, JsonSupport.write(payload), createdAt);
    }

    public Set<Long> insertRuntimeEvents(List<RuntimeEventInsert> events) {
        return Set.copyOf(mapper.insertRuntimeEvents(events));
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

    public void insertRuntimeToolCall(
            UUID id,
            UUID runId,
            UUID conversationId,
            String toolAlias,
            String componentKey,
            String componentVersion,
            String originalToolName,
            String toolCallId,
            long executionToken,
            Instant createdAt) {
        mapper.insertRuntimeToolCall(
                id,
                runId,
                conversationId,
                toolAlias,
                toolAlias,
                componentKey,
                componentVersion,
                originalToolName,
                toolCallId,
                executionToken,
                createdAt);
    }

    public void finishRuntimeToolCall(
            UUID id,
            UUID runId,
            String status,
            Map<String, Object> resultSummary,
            Integer durationMs,
            Instant finishedAt) {
        mapper.finishRuntimeToolCall(
                id,
                runId,
                status,
                JsonSupport.write(resultSummary == null ? Map.of() : resultSummary),
                durationMs,
                finishedAt);
    }

    public List<ToolCall> listToolCalls(UUID runId) {
        return mapper.listToolCalls(runId);
    }

    public Optional<RunEvent> eventByCursor(UUID conversationId, long cursor) {
        return mapper.eventByCursor(conversationId, cursor).stream().findFirst();
    }
}
