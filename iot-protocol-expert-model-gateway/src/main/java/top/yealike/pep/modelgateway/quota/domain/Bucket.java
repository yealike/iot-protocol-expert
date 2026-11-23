package top.yealike.pep.modelgateway.quota.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 账户的某一代额度窗口或总量桶。 */
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
