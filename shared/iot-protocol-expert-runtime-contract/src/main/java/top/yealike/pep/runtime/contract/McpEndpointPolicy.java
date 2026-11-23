package top.yealike.pep.runtime.contract;

/** MCP 版本的冻结入口策略。 */
public record McpEndpointPolicy(
        String endpoint,
        String authority,
        String contextMode,
        String path,
        String query,
        String serviceName,
        Integer timeoutMs,
        String authMode) {}
