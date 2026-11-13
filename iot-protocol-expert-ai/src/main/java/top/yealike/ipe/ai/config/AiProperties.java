package top.yealike.ipe.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** AI 服务配置。密钥只从环境变量注入，不落代码与日志。 */
@ConfigurationProperties(prefix = "ipe.ai")
public record AiProperties(
        String environment,
        String serviceToken,
        String opsBaseUrl,
        int requestTimeoutMs,
        Model model,
        Embedding embedding,
        Run run,
        Knowledge knowledge) {

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
