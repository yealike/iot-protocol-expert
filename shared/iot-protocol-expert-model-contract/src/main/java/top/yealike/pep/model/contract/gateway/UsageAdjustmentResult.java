package top.yealike.pep.model.contract.gateway;

import java.util.UUID;

/** 结算后追加用量调整的结果。 */
public record UsageAdjustmentResult(UUID modelCallId, int adjustmentNo, long deltaUnits) {}
