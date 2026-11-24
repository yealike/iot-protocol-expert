package top.yealike.pep.runtime.contract;

import java.util.List;
import java.util.UUID;

/** Runtime 提交的本地同步委派命令。 */
public record DelegateTaskRequest(
        UUID parentRunId,
        long executionToken,
        String instanceId,
        UUID sourceReleaseId,
        String delegateAlias,
        String idempotencyKey,
        String task,
        List<String> contextRefs,
        DelegateOutput expectedOutput,
        Integer deadlineSeconds) {}
