package top.yealike.ipe.protocol.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 平台端运行参数。 */
@ConfigurationProperties(prefix = "ipe.protocol")
public class ProtocolProperties {

    /** 云快充协议是否启用。 */
    private boolean ykcEnabled = true;

    /** TCP 监听端口，默认 8768（云快充测试端口约定）。 */
    private int tcpPort = 8768;

    /** OCPP 1.6J 是否启用。 */
    private boolean ocppEnabled = true;

    /** OCPP WebSocket 监听端口。 */
    private int ocppPort = 8769;

    /** OCPP WebSocket 路径前缀，实际连接地址为 ws://host:port{path}/{桩号}。 */
    private String ocppPath = "/ocpp";

    /** OCPP BootNotification 未携带枪数时的默认枪数。 */
    private int ocppDefaultConnectorCount = 2;

    /** OCPP 读空闲超时秒数。 */
    private int ocppIdleTimeoutSeconds = 300;

    private int bossThreads = 1;
    private int workerThreads = 8;

    /** 3DES 密钥（16/24 字节十六进制）。默认值是公开测试向量，仅用于演示；真实设备请设置 IPE_CRYPTO_KEY_HEX。 */
    private String cryptoKeyHex = "0123456789ABCDEFFEDCBA9876543210";

    /** 是否要求收发加密帧；false 时按明文收发（模拟器需一致）。 */
    private boolean encryptionEnabled = false;

    /** 未知桩首次登录是否自动建档。 */
    private boolean autoRegister = true;

    /** 读空闲超时秒数，超时断开。 */
    private int heartbeatTimeoutSeconds = 45;

    /** 下行业务命令等待应答秒数。 */
    private int pendingTimeoutSeconds = 10;

    /** 默认计费模型编号。 */
    private String defaultRateModelNo = "0001";

    private String defaultPilePrefix = "3201060003";

    /** 运维平台内部 API 地址。 */
    private String opsBaseUrl = "http://127.0.0.1:19082";

    /** 服务间身份令牌。 */
    private String opsServiceToken = "local-service-token";

    /** 内部 HTTP 超时毫秒。 */
    private int opsTimeoutMs = 5000;

    public boolean isYkcEnabled() {
        return ykcEnabled;
    }

    public void setYkcEnabled(boolean ykcEnabled) {
        this.ykcEnabled = ykcEnabled;
    }

    public boolean isOcppEnabled() {
        return ocppEnabled;
    }

    public void setOcppEnabled(boolean ocppEnabled) {
        this.ocppEnabled = ocppEnabled;
    }

    public int getOcppPort() {
        return ocppPort;
    }

    public void setOcppPort(int ocppPort) {
        this.ocppPort = ocppPort;
    }

    public String getOcppPath() {
        return ocppPath;
    }

    public void setOcppPath(String ocppPath) {
        this.ocppPath = ocppPath;
    }

    public int getOcppDefaultConnectorCount() {
        return ocppDefaultConnectorCount;
    }

    public void setOcppDefaultConnectorCount(int ocppDefaultConnectorCount) {
        this.ocppDefaultConnectorCount = ocppDefaultConnectorCount;
    }

    public int getOcppIdleTimeoutSeconds() {
        return ocppIdleTimeoutSeconds;
    }

    public void setOcppIdleTimeoutSeconds(int ocppIdleTimeoutSeconds) {
        this.ocppIdleTimeoutSeconds = ocppIdleTimeoutSeconds;
    }

    public int getTcpPort() {
        return tcpPort;
    }

    public void setTcpPort(int tcpPort) {
        this.tcpPort = tcpPort;
    }

    public int getBossThreads() {
        return bossThreads;
    }

    public void setBossThreads(int bossThreads) {
        this.bossThreads = bossThreads;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public String getCryptoKeyHex() {
        return cryptoKeyHex;
    }

    public void setCryptoKeyHex(String cryptoKeyHex) {
        this.cryptoKeyHex = cryptoKeyHex;
    }

    public boolean isEncryptionEnabled() {
        return encryptionEnabled;
    }

    public void setEncryptionEnabled(boolean encryptionEnabled) {
        this.encryptionEnabled = encryptionEnabled;
    }

    public boolean isAutoRegister() {
        return autoRegister;
    }

    public void setAutoRegister(boolean autoRegister) {
        this.autoRegister = autoRegister;
    }

    public int getHeartbeatTimeoutSeconds() {
        return heartbeatTimeoutSeconds;
    }

    public void setHeartbeatTimeoutSeconds(int heartbeatTimeoutSeconds) {
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
    }

    public int getPendingTimeoutSeconds() {
        return pendingTimeoutSeconds;
    }

    public void setPendingTimeoutSeconds(int pendingTimeoutSeconds) {
        this.pendingTimeoutSeconds = pendingTimeoutSeconds;
    }

    public String getDefaultRateModelNo() {
        return defaultRateModelNo;
    }

    public void setDefaultRateModelNo(String defaultRateModelNo) {
        this.defaultRateModelNo = defaultRateModelNo;
    }

    public String getOpsBaseUrl() {
        return opsBaseUrl;
    }

    public void setOpsBaseUrl(String opsBaseUrl) {
        this.opsBaseUrl = opsBaseUrl;
    }

    public String getOpsServiceToken() {
        return opsServiceToken;
    }

    public void setOpsServiceToken(String opsServiceToken) {
        this.opsServiceToken = opsServiceToken;
    }

    public int getOpsTimeoutMs() {
        return opsTimeoutMs;
    }

    public void setOpsTimeoutMs(int opsTimeoutMs) {
        this.opsTimeoutMs = opsTimeoutMs;
    }

    public String getDefaultPilePrefix() {
        return defaultPilePrefix;
    }

    public void setDefaultPilePrefix(String defaultPilePrefix) {
        this.defaultPilePrefix = defaultPilePrefix;
    }
}
