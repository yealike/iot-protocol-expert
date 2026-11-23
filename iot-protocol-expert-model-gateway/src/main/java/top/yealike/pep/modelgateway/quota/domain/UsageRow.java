package top.yealike.pep.modelgateway.quota.domain;

import java.time.OffsetDateTime;

/** 用量明细查询行。 */
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
