package top.yealike.pep.runtime.execution;

/** ToolResultBlock 上的内部审计元数据键；随 SDK 事件透传，不参与业务输出。 */
public final class ToolAuditMetadata {

    public static final String ERROR_CODE = "ipe.error_code";
    public static final String DECISION = "ipe.authorization.decision";
    public static final String ALLOW = "ALLOW";
    public static final String DENIED = "DENIED";
    public static final String PENDING = "PENDING";

    private ToolAuditMetadata() {}
}
