package top.yealike.pep.control.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** AI 服务配置。密钥只从环境变量注入，不落代码与日志。 */
@ConfigurationProperties(prefix = "ipe.ai")
public record AiProperties(
        String environment,
        AiMcp mcp,
        int requestTimeoutMs,
        AiModel model,
        AiEmbedding embedding,
        AiRun run,
        AiKnowledge knowledge) {}
