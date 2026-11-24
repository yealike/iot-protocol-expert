package top.yealike.pep.runtime.contract;

/** Supervisor 发布清单中的委派限额。 */
public record DelegationPolicy(
        int maxDepth, int maxCallsPerRun, int maxParallel, long maxTotalTokens, long maxWallClockMs) {

    public static final int HARD_MAX_DEPTH = 3;
    public static final int MAX_DELEGATES = 8;
    public static final int HARD_MAX_CALLS = 10;
    public static final long HARD_MAX_TIME_MS = 300_000L;
    public static final long HARD_MAX_TOKENS = 100_000L;
    public static final String TOOL_NAME = "delegate_agent";

    /** 按父 Run 的时限生成默认策略。 */
    public static DelegationPolicy defaults(long runTimeoutMs) {
        return new DelegationPolicy(2, 4, 1, HARD_MAX_TOKENS, runTimeoutMs);
    }

    /** 检查一期部署支持的限额。 */
    public boolean validFor(long runTimeoutMs) {
        return maxDepth >= 1
                && maxDepth <= HARD_MAX_DEPTH
                && maxCallsPerRun >= 1
                && maxCallsPerRun <= HARD_MAX_CALLS
                && maxParallel == 1
                && maxTotalTokens >= 1
                && maxTotalTokens <= HARD_MAX_TOKENS
                && maxWallClockMs >= 1
                && maxWallClockMs <= Math.min(runTimeoutMs, HARD_MAX_TIME_MS);
    }
}
