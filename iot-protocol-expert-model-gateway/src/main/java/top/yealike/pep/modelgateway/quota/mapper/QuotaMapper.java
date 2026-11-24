package top.yealike.pep.modelgateway.quota.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.modelgateway.quota.domain.Account;
import top.yealike.pep.modelgateway.quota.domain.Bucket;
import top.yealike.pep.modelgateway.quota.domain.Call;
import top.yealike.pep.modelgateway.quota.domain.ChangeRequest;
import top.yealike.pep.modelgateway.quota.domain.Charge;
import top.yealike.pep.modelgateway.quota.domain.Policy;
import top.yealike.pep.modelgateway.quota.domain.Reservation;
import top.yealike.pep.modelgateway.quota.domain.UsageRow;

public interface QuotaMapper {

    OffsetDateTime now();

    int lockAdvisory(@Param("value") String value);

    List<Account> lockAccounts(@Param("scopeKind") String scopeKind, @Param("subjectId") String subjectId);

    List<Account> accounts(@Param("scopeKind") String scopeKind, @Param("subjectId") String subjectId);

    Account findAccount(@Param("id") UUID id);

    Account lockAccount(@Param("id") UUID id);

    Policy findPolicy(@Param("policyKey") String policyKey, @Param("revision") long revision);

    Bucket findBucket(@Param("id") UUID id);

    Bucket lockBucket(@Param("id") UUID id);

    Long nextGeneration(@Param("accountId") UUID accountId);

    int insertBucket(
            @Param("id") UUID id,
            @Param("accountId") UUID accountId,
            @Param("policyRevision") long policyRevision,
            @Param("generation") long generation,
            @Param("startAt") OffsetDateTime startAt,
            @Param("endAt") OffsetDateTime endAt,
            @Param("activationState") String activationState,
            @Param("limitUnits") long limitUnits);

    int setCurrentBucket(
            @Param("accountId") UUID accountId,
            @Param("bucketId") UUID bucketId,
            @Param("expectedVersion") long expectedVersion);

    int updateBucketState(@Param("bucketId") UUID bucketId, @Param("state") String state);

    int updateBucketPolicy(
            @Param("bucketId") UUID bucketId,
            @Param("limitUnits") long limitUnits,
            @Param("policyRevision") long policyRevision);

    int addHeld(@Param("bucketId") UUID bucketId, @Param("delta") long delta);

    int settleBucket(
            @Param("bucketId") UUID bucketId,
            @Param("releasedHeld") long releasedHeld,
            @Param("settledUnits") long settledUnits);

    int settleReleasedBucket(@Param("bucketId") UUID bucketId, @Param("settledUnits") long settledUnits);

    Reservation findReservationByCall(@Param("modelCallId") UUID modelCallId);

    Reservation lockReservation(@Param("id") UUID id);

    List<Charge> lockCharges(@Param("reservationId") UUID reservationId);

    Call findCall(@Param("logicalCallId") UUID logicalCallId, @Param("attemptNo") int attemptNo);

    Call findCallById(@Param("id") UUID id);

    /** 创建调用记录及父子 Run 归属。 */
    int insertCall(
            @Param("call") Call call,
            @Param("rootRunId") String rootRunId,
            @Param("parentRunId") String parentRunId,
            @Param("a2aTaskId") String a2aTaskId,
            @Param("estimatedTokens") long estimatedTokens);

    /** 汇总根 Run 的结算和未结算 Token。 */
    long rootTokens(@Param("rootRunId") String rootRunId);

    int insertReservation(
            @Param("id") UUID id,
            @Param("modelCallId") UUID modelCallId,
            @Param("units") long units,
            @Param("dispatchDeadline") OffsetDateTime dispatchDeadline);

    int insertCharge(
            @Param("id") UUID id,
            @Param("reservationId") UUID reservationId,
            @Param("accountId") UUID accountId,
            @Param("bucketId") UUID bucketId,
            @Param("units") long units);

    int updateReservationState(@Param("id") UUID id, @Param("state") String state);

    int markReservationDispatched(@Param("id") UUID id);

    int updateChargeState(@Param("id") UUID id, @Param("state") String state, @Param("settledUnits") Long settledUnits);

    int updateCallState(
            @Param("id") UUID id, @Param("state") String state, @Param("providerRequestId") String providerRequestId);

    int markCallDispatched(@Param("id") UUID id);

    int appendLedger(
            @Param("id") UUID id,
            @Param("accountId") UUID accountId,
            @Param("bucketId") UUID bucketId,
            @Param("reservationId") UUID reservationId,
            @Param("eventType") String eventType,
            @Param("deltaUsed") long deltaUsed,
            @Param("deltaHeld") long deltaHeld,
            @Param("sourceEventId") String sourceEventId);

    int insertUsage(
            @Param("id") UUID id,
            @Param("modelCallId") UUID modelCallId,
            @Param("inputTokens") long inputTokens,
            @Param("outputTokens") long outputTokens,
            @Param("cacheReadTokens") long cacheReadTokens,
            @Param("cacheWriteTokens") long cacheWriteTokens,
            @Param("reasoningTokens") long reasoningTokens,
            @Param("quotaUnits") long quotaUnits,
            @Param("quality") String quality,
            @Param("providerUsageHash") String providerUsageHash,
            @Param("rawUsage") String rawUsage);

    int markDebt(@Param("accountId") UUID accountId, @Param("debtUnits") long debtUnits);

    int updateAccountState(@Param("accountId") UUID accountId, @Param("state") String state);

    List<Reservation> expiredHeldReservations(@Param("limit") int limit);

    List<Reservation> expiredDispatchedReservations(@Param("cutoff") OffsetDateTime cutoff, @Param("limit") int limit);

    Long pendingReconciliation(@Param("scopeKind") String scopeKind, @Param("subjectId") String subjectId);

    List<Charge> chargesForCall(@Param("modelCallId") UUID modelCallId);

    Integer nextAdjustmentNo(@Param("modelCallId") UUID modelCallId);

    int adjustBucketUsed(@Param("bucketId") UUID bucketId, @Param("delta") long delta);

    List<UsageRow> usageRows(
            @Param("scopeKind") String scopeKind,
            @Param("subjectId") String subjectId,
            @Param("limit") int limit,
            @Param("modelKey") String modelKey,
            @Param("projectId") String projectId);

    Integer maxPolicyRevision(@Param("policyKey") String policyKey);

    int insertPolicy(
            @Param("id") UUID id,
            @Param("policyKey") String policyKey,
            @Param("revision") long revision,
            @Param("mode") String mode,
            @Param("limitUnits") long limitUnits,
            @Param("durationSeconds") Long durationSeconds,
            @Param("effectiveAt") OffsetDateTime effectiveAt,
            @Param("expiresAt") OffsetDateTime expiresAt);

    int insertAccount(
            @Param("id") UUID id,
            @Param("scopeKind") String scopeKind,
            @Param("subjectId") String subjectId,
            @Param("policyKey") String policyKey,
            @Param("revision") long revision);

    int updateAccountRevision(@Param("accountId") UUID accountId, @Param("revision") long revision);

    int insertChangeRequest(
            @Param("id") UUID id,
            @Param("accountId") UUID accountId,
            @Param("oldRevision") Long oldRevision,
            @Param("newRevision") Long newRevision,
            @Param("action") String action,
            @Param("effectiveMode") String effectiveMode,
            @Param("reason") String reason,
            @Param("actorId") String actorId,
            @Param("idempotencyKey") String idempotencyKey);

    ChangeRequest findChangeRequest(@Param("actorId") String actorId, @Param("idempotencyKey") String idempotencyKey);

    int insertAdjustment(
            @Param("id") UUID id,
            @Param("modelCallId") UUID modelCallId,
            @Param("adjustmentNo") int adjustmentNo,
            @Param("deltaUnits") long deltaUnits,
            @Param("reason") String reason,
            @Param("evidenceRef") String evidenceRef,
            @Param("operatorId") String operatorId,
            @Param("idempotencyKey") String idempotencyKey);
}
