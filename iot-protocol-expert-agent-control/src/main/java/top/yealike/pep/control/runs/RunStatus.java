package top.yealike.pep.control.runs;

public final class RunStatus {

    private RunStatus() {}

    public static final String DISPATCHING = "DISPATCHING";
    public static final String RUNNING = "RUNNING";
    public static final String CANCEL_REQUESTED = "CANCEL_REQUESTED";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
}
