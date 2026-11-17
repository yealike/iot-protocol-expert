package top.yealike.pep.model.contract;

/**
 * 归一化后的模型用量。
 *
 * <p>quota_units 首期等于输入与输出之和；缓存与 reasoning 只保留明细，避免同一供应商字段被重复相加。
 */
public record ModelUsage(
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        long reasoningTokens,
        long quotaUnits,
        String quality) {

    public ModelUsage {
        if (inputTokens < 0
                || outputTokens < 0
                || cacheReadTokens < 0
                || cacheWriteTokens < 0
                || reasoningTokens < 0
                || quotaUnits < 0) {
            throw new IllegalArgumentException("usage 不能为负数");
        }
    }

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
