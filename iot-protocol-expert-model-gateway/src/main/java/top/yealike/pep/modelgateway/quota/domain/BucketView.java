package top.yealike.pep.modelgateway.quota.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 预占响应中的额度桶视图。 */
public record BucketView(
        UUID accountId,
        String policyKey,
        String mode,
        long limitUnits,
        long usedUnits,
        long heldUnits,
        long remainingUnits,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        String activationState) {}
