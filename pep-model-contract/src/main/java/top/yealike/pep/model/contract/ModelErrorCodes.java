package top.yealike.pep.model.contract;

/** 网关返回的稳定错误码；前端与 Runtime 按码分支，不解析中文消息。 */
public final class ModelErrorCodes {

    public static final String UNAUTHORIZED = "MODEL_GATEWAY_UNAUTHORIZED";
    public static final String MODEL_NOT_ALLOWED = "MODEL_NOT_ALLOWED";
    public static final String QUOTA_EXHAUSTED = "QUOTA_EXHAUSTED";
    public static final String QUOTA_TOTAL_EXHAUSTED = "QUOTA_TOTAL_EXHAUSTED";
    public static final String QUOTA_PENDING_RECONCILIATION = "QUOTA_PENDING_RECONCILIATION";
    public static final String QUOTA_UNAVAILABLE = "QUOTA_UNAVAILABLE";
    public static final String MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";
    public static final String REQUEST_INVALID = "MODEL_REQUEST_INVALID";
    public static final String CALL_CONFLICT = "MODEL_CALL_CONFLICT";

    private ModelErrorCodes() {}
}
