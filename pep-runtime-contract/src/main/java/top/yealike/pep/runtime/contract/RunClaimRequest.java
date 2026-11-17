package top.yealike.pep.runtime.contract;

import java.util.UUID;

public record RunClaimRequest(UUID runId, long executionToken, String instanceId, String requestId) {}
