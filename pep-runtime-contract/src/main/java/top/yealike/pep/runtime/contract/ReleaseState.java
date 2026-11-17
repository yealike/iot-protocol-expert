package top.yealike.pep.runtime.contract;

/** 不可变发布的可用状态。发布内容本身不可修改。 */
public enum ReleaseState {
    STAGED,
    ACTIVE,
    RETIRED,
    REVOKED,
    FAILED
}
