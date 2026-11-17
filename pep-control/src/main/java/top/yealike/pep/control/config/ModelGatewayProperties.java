package top.yealike.pep.control.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 控制服务访问模型网关的地址与签名配置。上游供应商密钥不在这里。 */
@ConfigurationProperties(prefix = "pep.control.model-gateway")
public class ModelGatewayProperties {

    private String baseUrl = "http://127.0.0.1:19085";
    private String serviceToken = "";
    private String runTokenSecret = "";
    private int runTokenTtlSeconds = 900;
    private String defaultTenantId = "00000000-0000-0000-0000-000000000001";
    private String defaultProjectId = "00000000-0000-0000-0000-000000000002";
    private String evaluatorSubject = "ipe-evaluator";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getServiceToken() {
        return serviceToken;
    }

    public void setServiceToken(String serviceToken) {
        this.serviceToken = serviceToken;
    }

    public String getRunTokenSecret() {
        return runTokenSecret;
    }

    public void setRunTokenSecret(String runTokenSecret) {
        this.runTokenSecret = runTokenSecret;
    }

    public int getRunTokenTtlSeconds() {
        return runTokenTtlSeconds;
    }

    public void setRunTokenTtlSeconds(int runTokenTtlSeconds) {
        this.runTokenTtlSeconds = runTokenTtlSeconds;
    }

    public String getDefaultTenantId() {
        return defaultTenantId;
    }

    public void setDefaultTenantId(String defaultTenantId) {
        this.defaultTenantId = defaultTenantId;
    }

    public String getDefaultProjectId() {
        return defaultProjectId;
    }

    public void setDefaultProjectId(String defaultProjectId) {
        this.defaultProjectId = defaultProjectId;
    }

    public String getEvaluatorSubject() {
        return evaluatorSubject;
    }

    public void setEvaluatorSubject(String evaluatorSubject) {
        this.evaluatorSubject = evaluatorSubject;
    }
}
