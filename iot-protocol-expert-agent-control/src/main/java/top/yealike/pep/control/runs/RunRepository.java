package top.yealike.pep.control.runs;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.pep.control.runs.RunRecords.Run;
import top.yealike.pep.control.runs.RunRecords.RunEvent;
import top.yealike.pep.control.runs.RunRecords.RuntimeEventInsert;
import top.yealike.pep.control.runs.RunRecords.ToolCall;
import top.yealike.pep.control.runs.mapper.RunMapper;
import top.yealike.pep.control.support.JsonSupport;

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
    @Transactional
    public Optional<Run> claimNextForDispatch(
            String dispatchOwner, int leaseSeconds, Instant deadlineAt, int perUserActiveLimit) {
        int normalizedLimit = Math.max(1, perUserActiveLimit);
        List<UUID> candidates = mapper.selectNextQueued(normalizedLimit);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        UUID id = candidates.getFirst();
        Run candidate = find(id).orElse(null);
        if (candidate == null) {
            return Optional.empty();
        }
        // 多 Control 必须按用户串行认领；advisory xact lock 与事务同生命周期。
        mapper.lockQuota("ai-run-dispatch:user:" + candidate.actor());
        if (countUserActive(candidate.actor(), null) >= normalizedLimit) {
            return Optional.empty();
        }
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

    public boolean claimDispatchRetry(Run run, int leaseSeconds) {
        return mapper.claimDispatchRetry(run.id(), run.executionToken(), run.dispatchNextAttemptAt(), leaseSeconds) > 0;
    }

    public boolean failDispatch(UUID id, long executionToken, String code, String message) {
        return mapper.failDispatch(id, executionToken, code, message) > 0;
    }

    public boolean releaseDispatch(UUID id, long executionToken, Instant nextAttemptAt) {
        return mapper.releaseDispatch(id, executionToken, nextAttemptAt) > 0;
    }

    public boolean heartbeat(UUID id, long executionToken, String instanceId, int leaseSeconds) {
        return mapper.heartbeat(id, executionToken, instanceId, leaseSeconds) > 0;
    }



    public boolean markCompleted(UUID id, long executionToken, String instanceId, Map<String, Object> usage) {
        return mapper.markCompletedWithToken(JsonSupport.write(usage), id, executionToken, instanceId) > 0;
    }


    public boolean markFailed(
            UUID id, long executionToken, String instanceId, String code, String message, Map<String, Object> usage) {
        return mapper.markFailedWithToken(code, message, JsonSupport.write(usage), id, executionToken, instanceId) > 0;
    }

    /** Control 权威终结已过期或已超时的 Run；返回终结后的状态，CAS 失败返回 null。 */
    public String terminateExpired(UUID id, long executionToken, String instanceId, String code, String message) {
        return mapper.terminateExpired(id, executionToken, instanceId, code, message);
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



    public int countQueued() {
        Integer count = mapper.countQueued();
        return count == null ? 0 : count;
    }

    public int countUserQueued(String actor, UUID excludeConversationId) {
        Integer count = mapper.countUserQueued(actor, excludeConversationId);
        return count == null ? 0 : count;
    }

    public int countUserActive(String actor, UUID excludeConversationId) {
        Integer count = mapper.countUserActive(actor, excludeConversationId);
        return count == null ? 0 : count;
    }

    public void lockQuota(String scope) {
        mapper.lockQuota(scope);
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


    // ------------------------------------------------------------ tool calls


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
            String decision,
            String denyReason,
            Map<String, Object> resultSummary,
            Integer durationMs,
            Instant finishedAt) {
        mapper.finishRuntimeToolCall(
                id,
                runId,
                status,
                decision,
                denyReason,
                JsonSupport.write(resultSummary == null ? Map.of() : resultSummary),
                durationMs,
                finishedAt);
    }

    public void updateRuntimeToolArguments(UUID id, UUID runId, Map<String, Object> argsSummary) {
        mapper.updateRuntimeToolArguments(id, runId, JsonSupport.write(argsSummary == null ? Map.of() : argsSummary));
    }

    public List<ToolCall> listToolCalls(UUID runId) {
        return mapper.listToolCalls(runId);
    }
}
