package top.yealike.pep.modelgateway.quota.domain;

import java.util.UUID;

/** 逻辑模型调用的计量主体快照。 */
public record Call(
        UUID id,
        UUID logicalCallId,
        int attemptNo,
        String billingSubjectId,
        String scopeKind,
        String actorId,
        String tenantId,
        String projectId,
        String taskId,
        String runId,
        String modelProfileKey,
        String provider,
        String model,
        String requestHash,
        String state) {}
