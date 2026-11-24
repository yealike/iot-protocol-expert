package top.yealike.pep.modelgateway.quota.domain;

import java.util.UUID;
import top.yealike.pep.model.contract.RunTokenLineage;

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
        long reservedUnits,
        RunTokenLineage lineage,
        long estimatedTokens) {

    /** 构造没有委派归属的预占命令。 */
    public ReservationRequest(
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
            long reservedUnits) {
        this(
                logicalCallId,
                attemptNo,
                requestHash,
                scopeKind,
                subjectId,
                actorId,
                tenantId,
                projectId,
                taskId,
                runId,
                modelProfileKey,
                provider,
                model,
                reservedUnits,
                null,
                0);
    }
}
