package top.yealike.pep.control.runs.delegation;

/** 一期支持的同步委派状态。 */
public enum A2aTaskState {
    SUBMITTED,
    WORKING,
    CANCEL_REQUESTED,
    COMPLETED,
    FAILED,
    CANCELED,
    REJECTED;

    /** 判断 Task 是否已收敛。 */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED || this == REJECTED;
    }
}
