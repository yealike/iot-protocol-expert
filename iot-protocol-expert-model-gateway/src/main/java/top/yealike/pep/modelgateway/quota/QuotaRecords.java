package top.yealike.pep.modelgateway.quota;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class QuotaRecords {

    private QuotaRecords() {}

    public record Policy(
            UUID id,
            String policyKey,
            long revision,
            String mode,
            long limitUnits,
            Long durationSeconds,
            OffsetDateTime effectiveAt,
            OffsetDateTime expiresAt) {}

    public record Account(
            UUID id,
            String scopeKind,
            String subjectId,
            String policyKey,
            long activeRevision,
            UUID currentBucketId,
            String state,
            long debtUnits,
            long version) {}

    public record Bucket(
            UUID id,
            UUID accountId,
            long policyRevision,
            long generation,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            String activationState,
            long limitUnits,
            long usedUnits,
            long heldUnits) {

        public long remaining() {
            return limitUnits - usedUnits - heldUnits;
        }
    }

    public record Reservation(UUID id, UUID modelCallId, long reservedUnits, String state) {}

    public record ChangeRequest(UUID accountId, Long newRevision, String action, String effectiveMode, String reason) {}

    public record Charge(
            UUID id,
            UUID reservationId,
            UUID accountId,
            UUID bucketId,
            long reservedUnits,
            Long settledUnits,
            String state) {}

    public record Call(
            UUID id,
            UUID logicalCallId,
            int attemptNo,
            String billingSubjectId,
            String scopeKind,
            String actorId,
            String tenantId,
            String projectId,
            String taskId,
            String runId,
            String modelProfileKey,
            String provider,
            String model,
            String requestHash,
            String state) {}

    public record UsageRow(
            String callId,
            String logicalCallId,
            int attemptNo,
            String model,
            String provider,
            String projectId,
            String runId,
            String state,
            OffsetDateTime createdAt,
            Long inputTokens,
            Long outputTokens,
            Long quotaUnits,
            String quality) {}
}
