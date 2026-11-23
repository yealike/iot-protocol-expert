package top.yealike.pep.modelgateway.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 模型网关配置。共享令牌与主密钥只从环境变量注入，toString 不输出秘密。 */
@ConfigurationProperties(prefix = "pep.gateway")
public record GatewayProperties(
        String serviceToken,
        String runTokenSecret,
        String credentialMasterKey,
        String credentialMasterKeyFile,
        String publicBaseUrl,
        int connectTimeoutMs,
        int readTimeoutMs,
        int dispatchDeadlineSeconds,
        int reservationTtlSeconds,
        GatewayCounting counting) {

    public GatewayProperties {
        serviceToken = nullToEmpty(serviceToken);
        runTokenSecret = nullToEmpty(runTokenSecret);
        credentialMasterKey = nullToEmpty(credentialMasterKey);
        credentialMasterKeyFile = nullToEmpty(credentialMasterKeyFile);
        publicBaseUrl = nullToEmpty(publicBaseUrl);
        connectTimeoutMs = connectTimeoutMs <= 0 ? 10_000 : connectTimeoutMs;
        readTimeoutMs = readTimeoutMs <= 0 ? 300_000 : readTimeoutMs;
        dispatchDeadlineSeconds = dispatchDeadlineSeconds <= 0 ? 15 : dispatchDeadlineSeconds;
        reservationTtlSeconds = reservationTtlSeconds <= 0 ? 900 : reservationTtlSeconds;
        counting = counting == null ? GatewayCounting.defaults() : counting;
    }

    public static GatewayProperties defaults() {
        return new GatewayProperties("", "", "", "", "", 10_000, 300_000, 15, 900, GatewayCounting.defaults());
    }

    @Override
    public String toString() {
        return "GatewayProperties[serviceToken=<redacted>"
                + ", runTokenSecret=<redacted>"
                + ", credentialMasterKey=<redacted>"
                + ", credentialMasterKeyFile=" + (credentialMasterKeyFile.isBlank() ? "<empty>" : "<configured>")
                + ", publicBaseUrl=" + publicBaseUrl
                + ", connectTimeoutMs=" + connectTimeoutMs
                + ", readTimeoutMs=" + readTimeoutMs
                + ", dispatchDeadlineSeconds=" + dispatchDeadlineSeconds
                + ", reservationTtlSeconds=" + reservationTtlSeconds
                + ", counting=" + counting
                + "]";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
