package top.yealike.ipe.ops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ipe.ops")
public class OpsProperties {

    private String aiMcpToken = "";
    private String environment = "local";
    private String serviceToken = "local-service-token";
    private String simulatorToken = "local-simulator-token";
    private String protocolBaseUrl = "http://127.0.0.1:19080";
    private int protocolTimeoutMs = 5000;
    private boolean autoRegister = true;
    private String defaultStationCode = "ST-DEMO";
    private String defaultStationName = "演示充电站";
    private String lokiUrl = "";
    private final Model model = new Model();

    public static class Model {
        private boolean enabled;
        private String baseUrl = "";
        private String apiKey = "";
        private String name = "deepseek-chat";
        private int timeoutMs = 15000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

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

    public String getDefaultStationCode() {
        return defaultStationCode;
    }

    public void setDefaultStationCode(String defaultStationCode) {
        this.defaultStationCode = defaultStationCode;
    }

    public String getDefaultStationName() {
        return defaultStationName;
    }

    public String getLokiUrl() {
        return lokiUrl;
    }

    public void setLokiUrl(String lokiUrl) {
        this.lokiUrl = lokiUrl;
    }

    public void setDefaultStationName(String defaultStationName) {
        this.defaultStationName = defaultStationName;
    }

    public Model getModel() {
        return model;
    }
}
