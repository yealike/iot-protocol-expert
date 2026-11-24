package top.yealike.pep.control.runs.delegation;

import java.util.UUID;

/** 通过 Task 版本执行一次状态迁移。 */
public record A2aTaskTransition(
        UUID id,
        long version,
        A2aTaskState state,
        String resultJson,
        String usageJson,
        String errorCode,
        String errorMessage) {}
