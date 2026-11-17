package top.yealike.pep.runtime.contract;

import java.util.UUID;

public record RunDispatchReceipt(
        UUID runId, long executionToken, UUID receiptId, String instanceId, boolean accepted, String reason) {}
