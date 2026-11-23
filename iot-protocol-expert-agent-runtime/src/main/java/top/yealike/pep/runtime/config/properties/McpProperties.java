package top.yealike.pep.runtime.config.properties;

/** MCP 连接、调用与结果大小配置。 */
public record McpProperties(
        int connectTimeoutMs,
        int initTimeoutMs,
        int callTimeoutMs,
        int maxConnections,
        int idleSeconds,
        int threads,
        int queueCapacity,
        int maxResultChars) {

    public McpProperties {
        connectTimeoutMs = connectTimeoutMs <= 0 ? 3_000 : connectTimeoutMs;
        initTimeoutMs = initTimeoutMs <= 0 ? 10_000 : initTimeoutMs;
        callTimeoutMs = callTimeoutMs <= 0 ? 20_000 : callTimeoutMs;
        maxConnections = maxConnections <= 0 ? 32 : maxConnections;
        idleSeconds = idleSeconds <= 0 ? 300 : idleSeconds;
        threads = threads <= 0 ? 4 : threads;
        queueCapacity = queueCapacity < 0 ? 32 : queueCapacity;
        maxResultChars = maxResultChars <= 0 ? 65_536 : maxResultChars;
    }

    static McpProperties defaults() {
        return new McpProperties(3_000, 10_000, 20_000, 32, 300, 4, 32, 65_536);
    }
}
