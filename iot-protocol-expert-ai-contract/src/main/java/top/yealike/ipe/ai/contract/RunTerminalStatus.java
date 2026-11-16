package top.yealike.ipe.ai.contract;

/** Runtime 可提交的终态；RUNNING 等中间状态只由 Control 维护。 */
public enum RunTerminalStatus {
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT
}
