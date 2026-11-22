package top.yealike.pep.control.config.properties;

/** MCP 接入配置：地址可来自 Nacos 目录，密钥只从环境变量注入。 */
public record AiMcp(String token, String url, String gatewayBaseUrl, AiRegistry registry) {}
