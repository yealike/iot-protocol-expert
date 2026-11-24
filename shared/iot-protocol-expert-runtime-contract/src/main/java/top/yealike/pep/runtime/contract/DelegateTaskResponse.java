package top.yealike.pep.runtime.contract;

import java.util.List;
import java.util.UUID;

/** 内部 Broker 的 TASK 状态和用于同实例执行的子 Run 定位。 */
public record DelegateTaskResponse(
        UUID taskId,
        String state,
        boolean created,
        RunDispatchRequest child,
        List<String> allowedTools,
        String resultSummary,
        boolean truncated,
        RunUsage usage,
        String errorCode,
        String errorMessage) {}
