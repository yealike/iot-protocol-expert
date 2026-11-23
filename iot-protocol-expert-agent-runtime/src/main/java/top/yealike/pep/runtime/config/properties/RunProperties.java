package top.yealike.pep.runtime.config.properties;

/** Run 并发、排队与执行预算硬上限。 */
public record RunProperties(
        int maxActive,
        int queueCapacity,
        int maxIterationsHardLimit,
        int maxToolCallsHardLimit,
        long maxRunTimeoutMs,
        long heartbeatIntervalMs,
        long ownershipLossTimeoutMs) {

    public RunProperties {
        maxActive = maxActive <= 0 ? 4 : maxActive;
        queueCapacity = queueCapacity < 0 ? 8 : queueCapacity;
        maxIterationsHardLimit = maxIterationsHardLimit <= 0 ? 12 : maxIterationsHardLimit;
        maxToolCallsHardLimit = maxToolCallsHardLimit <= 0 ? 32 : maxToolCallsHardLimit;
        maxRunTimeoutMs = maxRunTimeoutMs <= 0 ? 300_000L : maxRunTimeoutMs;
        heartbeatIntervalMs = heartbeatIntervalMs <= 0 ? 5_000L : heartbeatIntervalMs;
        ownershipLossTimeoutMs = ownershipLossTimeoutMs <= 0 ? 10_000L : ownershipLossTimeoutMs;
    }

    static RunProperties defaults() {
        return new RunProperties(4, 8, 12, 32, 300_000L, 5_000L, 10_000L);
    }
}
