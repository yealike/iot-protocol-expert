package top.yealike.ipe.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** AI 服务配置。密钥只从环境变量注入，不落代码与日志。 */
@ConfigurationProperties(prefix = "ipe.ai")
public record AiProperties(
        String environment,
        Mcp mcp,
        int requestTimeoutMs,
        Model model,
        Embedding embedding,
        Run run,
        Knowledge knowledge) {

    /** MCP 接入配置：地址可来自 Nacos 目录，密钥只从环境变量注入。 */
    public record Mcp(String token, String url, String gatewayBaseUrl, Registry registry) {}

    /**
     * Nacos MCP Registry 目录。启用后由 {@code gateway-base-url} 与服务名拼出入口，并校验目录中的版本与工具清单。
     */
    public record Registry(boolean enabled, String serverName, String version, String namespace) {

        public boolean usable() {
            return enabled && serverName != null && !serverName.isBlank();
        }
    }

    public record Model(
            boolean enabled,
            String baseUrl,
            String apiKey,
            String name,
            int timeoutMs,
            int maxTokens,
            double temperature) {

        public boolean usable() {
            return enabled && baseUrl != null && !baseUrl.isBlank();
        }
    }

    public record Embedding(
            boolean enabled, String baseUrl, String apiKey, String name, int dimensions, int timeoutMs) {

        public boolean usable() {
            return enabled && baseUrl != null && !baseUrl.isBlank();
        }
    }

    public record Run(int maxConcurrent, int maxIters, int maxToolCalls, int timeoutMs, int maxContextMessages) {}

    public record Knowledge(int chunkSize, int chunkOverlap, int topK, int minScore, int maxDocumentBytes) {}
}
