package top.yealike.pep.modelgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pep.gateway")
public class GatewayProperties {

    private String serviceToken = "";
    private String runTokenSecret = "";
    private String credentialMasterKey = "";
    private String credentialMasterKeyFile = "";
    private String publicBaseUrl = "";
    private int connectTimeoutMs = 10_000;
    private int readTimeoutMs = 300_000;
    private int dispatchDeadlineSeconds = 15;
    private int reservationTtlSeconds = 900;
    private Counting counting = new Counting();

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

    public String getCredentialMasterKey() {
        return credentialMasterKey;
    }

    public void setCredentialMasterKey(String credentialMasterKey) {
        this.credentialMasterKey = credentialMasterKey;
    }

    public String getCredentialMasterKeyFile() {
        return credentialMasterKeyFile;
    }

    public void setCredentialMasterKeyFile(String credentialMasterKeyFile) {
        this.credentialMasterKeyFile = credentialMasterKeyFile;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getDispatchDeadlineSeconds() {
        return dispatchDeadlineSeconds;
    }

    public void setDispatchDeadlineSeconds(int dispatchDeadlineSeconds) {
        this.dispatchDeadlineSeconds = dispatchDeadlineSeconds;
    }

    public int getReservationTtlSeconds() {
        return reservationTtlSeconds;
    }

    public void setReservationTtlSeconds(int reservationTtlSeconds) {
        this.reservationTtlSeconds = reservationTtlSeconds;
    }

    public Counting getCounting() {
        return counting;
    }

    public void setCounting(Counting counting) {
        this.counting = counting;
    }

    public static class Counting {

        private String charsPerToken = "0.5";
        private int messageOverhead = 8;
        private int requestOverhead = 256;
        private boolean strict = false;

        public String getCharsPerToken() {
            return charsPerToken;
        }

        public void setCharsPerToken(String charsPerToken) {
            this.charsPerToken = charsPerToken;
        }

        public int getMessageOverhead() {
            return messageOverhead;
        }

        public void setMessageOverhead(int messageOverhead) {
            this.messageOverhead = messageOverhead;
        }

        public int getRequestOverhead() {
            return requestOverhead;
        }

        public void setRequestOverhead(int requestOverhead) {
            this.requestOverhead = requestOverhead;
        }

        public boolean isStrict() {
            return strict;
        }

        public void setStrict(boolean strict) {
            this.strict = strict;
        }
    }
}
