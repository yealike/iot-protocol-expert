package top.yealike.pep.model.contract.gateway;

import java.time.OffsetDateTime;

/** 单次模型调用用量明细。 */
public record UsageItem(
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
