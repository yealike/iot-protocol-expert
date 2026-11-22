package top.yealike.pep.control.config.properties;

public record AiRun(int maxConcurrent, int maxIters, int maxToolCalls, int timeoutMs, int maxContextMessages) {}
