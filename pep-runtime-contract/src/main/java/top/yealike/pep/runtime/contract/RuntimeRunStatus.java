package top.yealike.pep.runtime.contract;

import java.time.Instant;
import java.util.UUID;

/** Runtime 本地 Run 状态查询结果；不替代 Control 的持久 Run 状态。 */
public record RuntimeRunStatus(
        UUID runId,
        long executionToken,
        String status,
        String instanceId,
        Instant startedAt,
        Instant finishedAt,
        String errorCode,
        String errorMessage) {}
