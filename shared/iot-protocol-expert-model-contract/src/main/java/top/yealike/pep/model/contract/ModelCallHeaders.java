package top.yealike.pep.model.contract;

/** 模型调用在网关与 Runtime 之间传递的稳定头字段。 */
public final class ModelCallHeaders {

    public static final String LOGICAL_CALL_ID = "X-PEP-Logical-Call-Id";
    public static final String ATTEMPT_NO = "X-PEP-Attempt-No";
    public static final String RUN_ID = "X-PEP-Run-Id";

    private ModelCallHeaders() {}
}
