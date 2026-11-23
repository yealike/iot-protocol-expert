package top.yealike.pep.modelgateway.quota.domain;

import java.util.UUID;

/** 模型调用前的额度预占命令；同一逻辑调用与 attempt 必须幂等。 */
public record ReservationRequest(
        UUID logicalCallId,
        int attemptNo,
        String requestHash,
        String scopeKind,
        String subjectId,
        String actorId,
        String tenantId,
        String projectId,
        String taskId,
        String runId,
        String modelProfileKey,
        String provider,
        String model,
        long reservedUnits) {}
