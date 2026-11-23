package top.yealike.pep.runtime.contract;

/** Run 的模型调用用量。 */
public record RunUsage(
        Long inputTokens, Long outputTokens, Long totalTokens, Long modelCalls, Long toolCalls, Long durationMs) {}
