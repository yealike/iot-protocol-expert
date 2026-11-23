package top.yealike.pep.ops.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ipe.ops")
public class OpsProperties {

    private String aiMcpToken = "";
    private String environment = "local";
    private String serviceToken = "local-service-token";
    private String simulatorToken = "local-simulator-token";
    private String protocolBaseUrl = "http://127.0.0.1:19080";
    private int protocolTimeoutMs = 20000;
    private boolean autoRegister = true;
    private String lokiUrl = "";
    private final ModelProperties model = new ModelProperties();

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getAiMcpToken() {
        return aiMcpToken;
    }

    public void setAiMcpToken(String aiMcpToken) {
        this.aiMcpToken = aiMcpToken;
    }

    public String getServiceToken() {
        return serviceToken;
    }

    public void setServiceToken(String serviceToken) {
        this.serviceToken = serviceToken;
    }

    public String getSimulatorToken() {
        return simulatorToken;
    }

    public void setSimulatorToken(String simulatorToken) {
        this.simulatorToken = simulatorToken;
    }

    public String getProtocolBaseUrl() {
        return protocolBaseUrl;
    }

    public void setProtocolBaseUrl(String protocolBaseUrl) {
        this.protocolBaseUrl = protocolBaseUrl;
    }

    public int getProtocolTimeoutMs() {
        return protocolTimeoutMs;
    }

    public void setProtocolTimeoutMs(int protocolTimeoutMs) {
        this.protocolTimeoutMs = protocolTimeoutMs;
    }

    public boolean isAutoRegister() {
        return autoRegister;
    }

    public void setAutoRegister(boolean autoRegister) {
        this.autoRegister = autoRegister;
    }

    public String getLokiUrl() {
        return lokiUrl;
    }

    public void setLokiUrl(String lokiUrl) {
        this.lokiUrl = lokiUrl;
    }

    public ModelProperties getModel() {
        return model;
    }
}
