package top.yealike.pep.model.contract.gateway;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 单个额度策略的当前状态。 */
public record QuotaPolicyState(
        UUID accountId,
        String policyKey,
        String mode,
        String state,
        long limitUnits,
        Long durationSeconds,
        long usedUnits,
        long heldUnits,
        long remainingUnits,
        long availableUnits,
        boolean effective,
        boolean expiredWindow,
        String activationState,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        long debtUnits) {}
