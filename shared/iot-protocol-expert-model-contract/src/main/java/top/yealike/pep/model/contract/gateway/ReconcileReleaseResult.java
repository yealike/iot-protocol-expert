package top.yealike.pep.model.contract.gateway;

import java.util.UUID;

/** 释放对账结果。 */
public record ReconcileReleaseResult(UUID reservationId, String state) {}
