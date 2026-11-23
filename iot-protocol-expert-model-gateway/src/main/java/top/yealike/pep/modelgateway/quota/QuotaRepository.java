package top.yealike.pep.modelgateway.quota;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import top.yealike.pep.model.contract.ModelUsage;
import top.yealike.pep.model.contract.gateway.UsageItem;
import top.yealike.pep.modelgateway.quota.domain.Account;
import top.yealike.pep.modelgateway.quota.domain.Bucket;
import top.yealike.pep.modelgateway.quota.domain.Call;
import top.yealike.pep.modelgateway.quota.domain.ChangeRequest;
import top.yealike.pep.modelgateway.quota.domain.Charge;
import top.yealike.pep.modelgateway.quota.domain.Policy;
import top.yealike.pep.modelgateway.quota.domain.Reservation;
import top.yealike.pep.modelgateway.quota.domain.UsageRow;
import top.yealike.pep.modelgateway.quota.mapper.QuotaMapper;

/** 配额账本的 MyBatis 访问层；SQL 统一维护在 mapper/quota/QuotaMapper.xml。 */
@Repository
public class QuotaRepository {

    private final QuotaMapper mapper;

    public QuotaRepository(QuotaMapper mapper) {
        this.mapper = mapper;
    }

    public OffsetDateTime now() {
        return mapper.now();
    }

    public void lockAdvisory(String value) {
        mapper.lockAdvisory(value);
    }

    public List<Account> lockAccounts(String scopeKind, String subjectId) {
        return mapper.lockAccounts(scopeKind, subjectId);
    }

    public List<Account> accounts(String scopeKind, String subjectId) {
        return mapper.accounts(scopeKind, subjectId);
    }

    public Optional<Account> findAccount(UUID id) {
        return Optional.ofNullable(mapper.findAccount(id));
    }

    public Optional<Account> lockAccount(UUID id) {
        return Optional.ofNullable(mapper.lockAccount(id));
    }

    public Optional<Policy> findPolicy(String policyKey, long revision) {
        return Optional.ofNullable(mapper.findPolicy(policyKey, revision));
    }

    public Optional<Bucket> findBucket(UUID id) {
        return Optional.ofNullable(mapper.findBucket(id));
    }

    public Optional<Bucket> lockBucket(UUID id) {
        return Optional.ofNullable(mapper.lockBucket(id));
    }

    public long nextGeneration(UUID accountId) {
        Long value = mapper.nextGeneration(accountId);
        return value == null ? 0L : value;
    }

    public UUID insertBucket(
            UUID accountId,
            long policyRevision,
            long generation,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            String activationState,
            long limitUnits) {
        UUID id = UUID.randomUUID();
        mapper.insertBucket(id, accountId, policyRevision, generation, startAt, endAt, activationState, limitUnits);
        return id;
    }

    public void setCurrentBucket(UUID accountId, UUID bucketId, long expectedVersion) {
        int updated = mapper.setCurrentBucket(accountId, bucketId, expectedVersion);
        if (updated == 0) {
            throw new IllegalStateException("额度账户并发更新失败");
        }
    }

    public void updateBucketState(UUID bucketId, String state) {
        mapper.updateBucketState(bucketId, state);
    }

    public void updateBucketPolicy(UUID bucketId, long limitUnits, long policyRevision) {
        mapper.updateBucketPolicy(bucketId, limitUnits, policyRevision);
    }

    public void addHeld(UUID bucketId, long delta) {
        mapper.addHeld(bucketId, delta);
    }

    public void settleBucket(UUID bucketId, long releasedHeld, long settledUnits) {
        mapper.settleBucket(bucketId, releasedHeld, settledUnits);
    }

    public void settleReleasedBucket(UUID bucketId, long settledUnits) {
        mapper.settleReleasedBucket(bucketId, settledUnits);
    }

    public Optional<Reservation> findReservationByCall(UUID modelCallId) {
        return Optional.ofNullable(mapper.findReservationByCall(modelCallId));
    }

    public Optional<Reservation> lockReservation(UUID id) {
        return Optional.ofNullable(mapper.lockReservation(id));
    }

    public List<Charge> lockCharges(UUID reservationId) {
        return mapper.lockCharges(reservationId);
    }

    public Optional<Call> findCall(UUID logicalCallId, int attemptNo) {
        return Optional.ofNullable(mapper.findCall(logicalCallId, attemptNo));
    }

    public Optional<Call> findCallById(UUID id) {
        return Optional.ofNullable(mapper.findCallById(id));
    }

    public void insertCall(Call call) {
        mapper.insertCall(call);
    }

    public void insertReservation(UUID id, UUID modelCallId, long units, OffsetDateTime dispatchDeadline) {
        mapper.insertReservation(id, modelCallId, units, dispatchDeadline);
    }

    public void insertCharge(UUID id, UUID reservationId, UUID accountId, UUID bucketId, long units) {
        mapper.insertCharge(id, reservationId, accountId, bucketId, units);
    }

    public void updateReservationState(UUID id, String state) {
        mapper.updateReservationState(id, state);
    }

    public boolean markReservationDispatched(UUID id) {
        return mapper.markReservationDispatched(id) == 1;
    }

    public void updateChargeState(UUID id, String state, Long settledUnits) {
        mapper.updateChargeState(id, state, settledUnits);
    }

    public void updateCallState(UUID id, String state, String providerRequestId) {
        mapper.updateCallState(id, state, providerRequestId);
    }

    public void markCallDispatched(UUID id) {
        mapper.markCallDispatched(id);
    }

    public void appendLedger(
            UUID accountId,
            UUID bucketId,
            UUID reservationId,
            String eventType,
            long deltaUsed,
            long deltaHeld,
            String sourceEventId) {
        mapper.appendLedger(
                UUID.randomUUID(), accountId, bucketId, reservationId, eventType, deltaUsed, deltaHeld, sourceEventId);
    }

    public void insertUsage(UUID modelCallId, ModelUsage usage, String providerUsageHash, String rawUsage) {
        mapper.insertUsage(
                UUID.randomUUID(),
                modelCallId,
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheReadTokens(),
                usage.cacheWriteTokens(),
                usage.reasoningTokens(),
                usage.quotaUnits(),
                usage.quality(),
                providerUsageHash,
                rawUsage);
    }

    public void markDebt(UUID accountId, long debtUnits) {
        mapper.markDebt(accountId, debtUnits);
    }

    public void updateAccountState(UUID accountId, String state) {
        mapper.updateAccountState(accountId, state);
    }

    public List<Reservation> expiredHeldReservations(int limit) {
        return mapper.expiredHeldReservations(limit);
    }

    public List<Reservation> expiredDispatchedReservations(OffsetDateTime cutoff, int limit) {
        return mapper.expiredDispatchedReservations(cutoff, limit);
    }

    public long pendingReconciliation(String scopeKind, String subjectId) {
        Long value = mapper.pendingReconciliation(scopeKind, subjectId);
        return value == null ? 0 : value;
    }

    public List<Charge> chargesForCall(UUID modelCallId) {
        return mapper.chargesForCall(modelCallId);
    }

    public int nextAdjustmentNo(UUID modelCallId) {
        Integer value = mapper.nextAdjustmentNo(modelCallId);
        return value == null ? 1 : value;
    }

    public void adjustBucketUsed(UUID bucketId, long delta) {
        mapper.adjustBucketUsed(bucketId, delta);
    }

    public List<UsageItem> usageRows(String scopeKind, String subjectId, int limit, String modelKey, String projectId) {
        List<UsageRow> rows =
                mapper.usageRows(scopeKind, subjectId, Math.max(1, Math.min(limit, 500)), modelKey, projectId);
        return rows.stream()
                .map(row -> new UsageItem(
                        row.callId(),
                        row.logicalCallId(),
                        row.attemptNo(),
                        row.model(),
                        row.provider(),
                        row.projectId(),
                        row.runId(),
                        row.state(),
                        row.createdAt(),
                        row.inputTokens(),
                        row.outputTokens(),
                        row.quotaUnits(),
                        row.quality()))
                .toList();
    }

    public Integer maxPolicyRevision(String policyKey) {
        return mapper.maxPolicyRevision(policyKey);
    }

    public void insertPolicy(
            UUID id,
            String policyKey,
            long revision,
            String mode,
            long limitUnits,
            Long durationSeconds,
            OffsetDateTime effectiveAt,
            OffsetDateTime expiresAt) {
        mapper.insertPolicy(id, policyKey, revision, mode, limitUnits, durationSeconds, effectiveAt, expiresAt);
    }

    public void insertAccount(UUID id, String scopeKind, String subjectId, String policyKey, long revision) {
        mapper.insertAccount(id, scopeKind, subjectId, policyKey, revision);
    }

    public void updateAccountRevision(UUID accountId, long revision) {
        mapper.updateAccountRevision(accountId, revision);
    }

    public void insertChangeRequest(
            UUID accountId,
            Long oldRevision,
            Long newRevision,
            String action,
            String effectiveMode,
            String reason,
            String actorId,
            String idempotencyKey) {
        mapper.insertChangeRequest(
                UUID.randomUUID(),
                accountId,
                oldRevision,
                newRevision,
                action,
                effectiveMode,
                reason,
                actorId,
                idempotencyKey);
    }

    public Optional<ChangeRequest> findChangeRequest(String actorId, String idempotencyKey) {
        return Optional.ofNullable(mapper.findChangeRequest(actorId, idempotencyKey));
    }

    public void insertAdjustment(
            UUID modelCallId,
            int adjustmentNo,
            long deltaUnits,
            String reason,
            String evidenceRef,
            String operatorId,
            String idempotencyKey) {
        mapper.insertAdjustment(
                UUID.randomUUID(),
                modelCallId,
                adjustmentNo,
                deltaUnits,
                reason,
                evidenceRef,
                operatorId,
                idempotencyKey);
    }
}
