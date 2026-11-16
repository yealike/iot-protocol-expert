package top.yealike.ipe.ai.mapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import top.yealike.ipe.ai.run.RunRecords.Run;
import top.yealike.ipe.ai.run.RunRecords.RunEvent;
import top.yealike.ipe.ai.run.RunRecords.RuntimeEventInsert;
import top.yealike.ipe.ai.run.RunRecords.ToolCall;

public interface RunMapper {

    int insertRun(
            @Param("id") UUID id,
            @Param("conversationId") UUID conversationId,
            @Param("userMessageId") UUID userMessageId,
            @Param("actor") String actor,
            @Param("actorRolesJson") String actorRolesJson,
            @Param("actorAuthVersion") long actorAuthVersion,
            @Param("question") String question,
            @Param("agentKey") String agentKey,
            @Param("agentVersion") String agentVersion,
            @Param("agentReleaseId") UUID agentReleaseId,
            @Param("manifestDigest") String manifestDigest,
            @Param("modelKey") String modelKey,
            @Param("priority") int priority,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("policyVersion") String policyVersion,
            @Param("traceContext") String traceContext,
            @Param("createdAt") Instant createdAt);

    List<Run> find(@Param("id") UUID id);

    List<Run> findByIdempotency(
            @Param("conversationId") UUID conversationId, @Param("idempotencyKey") String idempotencyKey);

    List<Run> latest(@Param("conversationId") UUID conversationId);

    List<Run> current(@Param("conversationId") UUID conversationId);

    List<Run> listByConversation(@Param("conversationId") UUID conversationId, @Param("limit") int limit);

    List<UUID> selectNextQueued();

    int claimForDispatch(
            @Param("dispatchOwner") String dispatchOwner,
            @Param("leaseSeconds") int leaseSeconds,
            @Param("deadlineAt") Instant deadlineAt,
            @Param("id") UUID id);

    int markRunning(
            @Param("id") UUID id,
            @Param("executionToken") long executionToken,
            @Param("instanceId") String instanceId,
            @Param("leaseSeconds") int leaseSeconds);

    int bindDispatchTarget(
            @Param("id") UUID id,
            @Param("executionToken") long executionToken,
            @Param("instanceId") String instanceId,
            @Param("leaseSeconds") int leaseSeconds);

    int deferDispatch(
            @Param("id") UUID id,
            @Param("executionToken") long executionToken,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("leaseSeconds") int leaseSeconds);

    int releaseDispatch(
            @Param("id") UUID id,
            @Param("executionToken") long executionToken,
            @Param("nextAttemptAt") Instant nextAttemptAt);

    int heartbeat(
            @Param("id") UUID id,
            @Param("executionToken") long executionToken,
            @Param("instanceId") String instanceId,
            @Param("leaseSeconds") int leaseSeconds);

    int renewLease(
            @Param("leaseSeconds") int leaseSeconds, @Param("id") UUID id, @Param("leaseOwner") String leaseOwner);

    int transition(@Param("next") String next, @Param("id") UUID id, @Param("expected") String expected);

    int requestPause(@Param("id") UUID id);

    int markCompleted(@Param("usageJson") String usageJson, @Param("id") UUID id);

    int markFailed(
            @Param("code") String code,
            @Param("message") String message,
            @Param("usageJson") String usageJson,
            @Param("id") UUID id);

    int markCancelled(@Param("usageJson") String usageJson, @Param("id") UUID id);

    int markCompletedWithToken(
            @Param("usageJson") String usageJson,
            @Param("id") UUID id,
            @Param("executionToken") long executionToken,
            @Param("instanceId") String instanceId);

    int markFailedWithToken(
            @Param("code") String code,
            @Param("message") String message,
            @Param("usageJson") String usageJson,
            @Param("id") UUID id,
            @Param("executionToken") long executionToken,
            @Param("instanceId") String instanceId);

    int markCancelledWithToken(
            @Param("usageJson") String usageJson,
            @Param("id") UUID id,
            @Param("executionToken") long executionToken,
            @Param("instanceId") String instanceId);

    int markPaused(@Param("usageJson") String usageJson, @Param("id") UUID id);

    int requeuePaused(@Param("id") UUID id);

    int requestCancel(@Param("id") UUID id);

    int cancelQueued(@Param("id") UUID id);

    Integer countRunning();

    List<String> selectStatus(@Param("id") UUID id);

    List<Run> selectExpiredLeases(@Param("limit") int limit);

    List<Run> selectDispatchRetries(@Param("limit") int limit);

    List<Run> selectCancelPending(@Param("seconds") int seconds, @Param("limit") int limit);

    Long nextEventCursor(@Param("conversationId") UUID conversationId);

    Long reserveEventCursors(@Param("conversationId") UUID conversationId, @Param("count") int count);

    int insertEvent(
            @Param("conversationId") UUID conversationId,
            @Param("runId") UUID runId,
            @Param("cursor") long cursor,
            @Param("type") String type,
            @Param("payloadJson") String payloadJson,
            @Param("createdAt") Instant createdAt);

    List<Long> insertRuntimeEvents(@Param("events") List<RuntimeEventInsert> events);

    List<RunEvent> eventsAfter(
            @Param("conversationId") UUID conversationId, @Param("cursor") long cursor, @Param("limit") int limit);

    Long latestCursor(@Param("conversationId") UUID conversationId);

    int insertToolCall(
            @Param("id") UUID id,
            @Param("runId") UUID runId,
            @Param("conversationId") UUID conversationId,
            @Param("toolName") String toolName,
            @Param("status") String status,
            @Param("decision") String decision,
            @Param("denyReason") String denyReason,
            @Param("argsSummaryJson") String argsSummaryJson,
            @Param("createdAt") Instant createdAt);

    int finishToolCall(
            @Param("status") String status,
            @Param("resultSummaryJson") String resultSummaryJson,
            @Param("durationMs") int durationMs,
            @Param("id") UUID id);

    int insertRuntimeToolCall(
            @Param("id") UUID id,
            @Param("runId") UUID runId,
            @Param("conversationId") UUID conversationId,
            @Param("toolName") String toolName,
            @Param("toolAlias") String toolAlias,
            @Param("componentKey") String componentKey,
            @Param("componentVersion") String componentVersion,
            @Param("originalToolName") String originalToolName,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("executionToken") long executionToken,
            @Param("createdAt") Instant createdAt);

    int finishRuntimeToolCall(
            @Param("id") UUID id,
            @Param("runId") UUID runId,
            @Param("status") String status,
            @Param("resultSummaryJson") String resultSummaryJson,
            @Param("durationMs") Integer durationMs,
            @Param("finishedAt") Instant finishedAt);

    List<ToolCall> listToolCalls(@Param("runId") UUID runId);

    List<RunEvent> eventByCursor(@Param("conversationId") UUID conversationId, @Param("cursor") long cursor);
}
