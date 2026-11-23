package top.yealike.pep.runtime.execution.domain;

import top.yealike.pep.runtime.contract.RunTerminalStatus;

/** Runtime 对一次 Run 的终态收敛结果。 */
public record ExecutionOutcome(RunTerminalStatus status, String errorCode, String errorMessage) {}
