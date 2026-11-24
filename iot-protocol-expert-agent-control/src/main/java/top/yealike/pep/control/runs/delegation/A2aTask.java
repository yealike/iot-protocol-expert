package top.yealike.pep.control.runs.delegation;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import top.yealike.pep.runtime.contract.RunUsage;

/** 委派任务快照，子 Run 是执行终态的权威来源。 */
public record A2aTask(
        UUID id,
        UUID rootRunId,
        UUID parentRunId,
        UUID childRunId,
        UUID childConversationId,
        String contextId,
        UUID sourceAgentId,
        UUID sourceReleaseId,
        UUID targetAgentId,
        UUID targetReleaseId,
        String alias,
        int depth,
        A2aTaskState state,
        String idempotencyKey,
        String requestDigest,
        Map<String, Object> request,
        Map<String, Object> result,
        RunUsage usage,
        String errorCode,
        String errorMessage,
        Instant deadlineAt,
        long version,
        Instant createdAt,
        Instant completedAt) {}
