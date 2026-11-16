package top.yealike.ipe.ai.contract;

import java.util.UUID;

public record ToolAuthorizeRequest(
        UUID runId,
        long executionToken,
        String toolAlias,
        String argumentsDigest,
        long authVersion,
        String requestId) {}
