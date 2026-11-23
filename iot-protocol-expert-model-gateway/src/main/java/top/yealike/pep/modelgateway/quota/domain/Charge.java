package top.yealike.pep.modelgateway.quota.domain;

import java.util.UUID;

/** 预占在单个额度桶上的分摊明细。 */
public record Charge(
        UUID id,
        UUID reservationId,
        UUID accountId,
        UUID bucketId,
        long reservedUnits,
        Long settledUnits,
        String state) {}
