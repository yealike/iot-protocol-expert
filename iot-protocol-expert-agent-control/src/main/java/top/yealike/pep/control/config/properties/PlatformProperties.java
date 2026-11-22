package top.yealike.pep.control.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 在线 Agent 平台的运行配置。密钥只从环境变量或挂载文件读取。 */
@ConfigurationProperties(prefix = "ipe.ai.platform")
public record PlatformProperties(
        PlatformRegistry registry, PlatformSecurity security, PlatformRuntime runtime, PlatformIdentity identity) {}
