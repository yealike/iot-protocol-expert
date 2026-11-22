package top.yealike.pep.control.runs.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import top.yealike.pep.control.runs.RunStatus;

/** Run、事件与工具调用。状态迁移必须显式经过仓储方法，避免非法跳转。 */
public record Run(
        UUID id,
        UUID conversationId,
        UUID userMessageId,
        String actor,
        List<String> actorRoles,
        Long actorAuthVersion,
        String question,
        String agentKey,
        String agentVersion,
        String modelKey,
        String status,
        int priority,
        long queueSeq,
        String idempotencyKey,
        String policyVersion,
        String traceContext,
        String leaseOwner,
        Instant leaseUntil,
        Map<String, Object> budget,
        Map<String, Object> usage,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt,
        UUID agentReleaseId,
        String manifestDigest,
        String runtimeInstanceId,
        long executionToken,
        Instant lastHeartbeatAt,
        int dispatchAttempts,
        Instant deadlineAt,
        Instant dispatchNextAttemptAt,
        Instant cancelRequestedAt) {

    public boolean terminal() {
        return RunStatus.COMPLETED.equals(status)
                || RunStatus.FAILED.equals(status)
                || RunStatus.CANCELLED.equals(status);
    }
}
