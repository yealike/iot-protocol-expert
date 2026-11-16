package top.yealike.ipe.ai.contract;

/** 跨进程稳定错误码；不得把内部异常类名或 SQL 暴露给调用方。 */
public final class ErrorCodes {

    private ErrorCodes() {}

    public static final String REVISION_CONFLICT = "REVISION_CONFLICT";
    public static final String COMPONENT_IN_USE = "COMPONENT_IN_USE";
    public static final String RELEASE_NOT_ACTIVE = "RELEASE_NOT_ACTIVE";
    public static final String MISSING_DEPENDENCY = "MISSING_DEPENDENCY";
    public static final String SCHEMA_DRIFT = "SCHEMA_DRIFT";
    public static final String REGISTRY_UNAVAILABLE = "REGISTRY_UNAVAILABLE";
    public static final String NO_HEALTHY_RUNTIME = "NO_HEALTHY_RUNTIME";
    public static final String MCP_INIT_FAILED = "MCP_INIT_FAILED";
    public static final String MCP_TOOL_ERROR = "MCP_TOOL_ERROR";
    public static final String SKILL_PATH_DENIED = "SKILL_PATH_DENIED";
    public static final String TOOL_DENIED = "TOOL_DENIED";
    public static final String RUN_BUSY = "RUN_BUSY";
    public static final String EXECUTION_LOST = "EXECUTION_LOST";
    public static final String STALE_EXECUTION_TOKEN = "STALE_EXECUTION_TOKEN";
    public static final String EVENT_CURSOR_EXPIRED = "EVENT_CURSOR_EXPIRED";
    public static final String INTERNAL_SIGNATURE_INVALID = "INTERNAL_SIGNATURE_INVALID";
    public static final String CREDENTIAL_UNAVAILABLE = "CREDENTIAL_UNAVAILABLE";
}
