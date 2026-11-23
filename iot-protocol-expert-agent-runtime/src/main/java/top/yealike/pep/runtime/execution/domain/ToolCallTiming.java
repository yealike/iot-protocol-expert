package top.yealike.pep.runtime.execution.domain;

/** 单个工具调用的耗时起点。 */
public record ToolCallTiming(String alias, long startedNanos) {}
