package top.yealike.ipe.ai.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 在线 Agent 平台的运行配置。密钥只从环境变量或挂载文件读取。 */
@ConfigurationProperties(prefix = "ipe.ai.platform")
public record PlatformProperties(
        Registry registry, Security security, Runtime runtime, Identity identity, Bootstrap bootstrap) {

    public record Registry(
            boolean enabled,
            String serverAddr,
            String username,
            String password,
            String namespace,
            String group,
            String advertiseIp,
            String controlServiceName,
            String mcpGatewayBase,
            int cacheSeconds) {}

    public record Security(
            String masterKey,
            String masterKeyFile,
            String internalServiceName,
            String runtimeServiceName,
            String internalServiceKey,
            String internalKeyId,
            int clockSkewSeconds) {}

    public record Runtime(
            int maxDispatchAttempts,
            int leaseSeconds,
            int queueLimit,
            int perUserQueued,
            int perUserInFlight,
            String baseUrl,
            boolean directFallbackEnabled) {}

    public record Identity(String serviceName, String namespace, String group, String baseUrl, String serviceToken) {}

    public record Bootstrap(
            boolean enabled,
            String modelCredentialKey,
            String modelApiKey,
            String modelBaseUrl,
            String modelName,
            String skillVersion) {}
}
