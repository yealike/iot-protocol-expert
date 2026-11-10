package top.yealike.ipe.simulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 模拟器运行参数。默认连接本机平台。 */
@ConfigurationProperties(prefix = "ipe.simulator")
public class SimulatorProperties {

    private String platformHost = "127.0.0.1";
    private int platformPort = 8768;
    private int platformHttpPort = 19080;
    public int getPlatformHttpPort() { return platformHttpPort; }
    public void setPlatformHttpPort(int port) { this.platformHttpPort=port; }
    /** 桩编号：14 位数字（7 字节 BCD）。 */
    private String pileCode = "32010600000001";
    private int gunCount = 2;
    private String stateFile = "./data/simulator-state.properties";
    public String getStateFile() { return stateFile; }
    public void setStateFile(String stateFile) { this.stateFile = stateFile; }
    /** 协议版本字节：v1.6 = 16。 */
    private int protocolVersion = 0x10;
    private String programVersion = "v1.0.0";
    private String simCard = "89860000000000000000";
    private int networkType = 1;
    private int operator = 0;
    private int pileType = 0;

    /** 公开测试向量；真实设备请与平台一起设置 IPE_CRYPTO_KEY_HEX。 */
    private String cryptoKeyHex = "0123456789ABCDEFFEDCBA9876543210";
    private boolean encryptionEnabled = false;

    private int heartbeatSeconds = 10;
    private int idleRealtimeSeconds = 300;
    private int chargingRealtimeSeconds = 15;
    private boolean autoConnect = true;
    private int reconnectSeconds = 5;

    /** 交易记录重试间隔与次数（规范：30 秒重试，最多 3 次）。 */
    private int transactionRetrySeconds = 30;
    private int transactionMaxRetries = 3;

    /** 演示用额定功率参数。 */
    private double ratedVoltage = 750.0;
    private double ratedCurrent = 100.0;

    public String getPlatformHost() {
        return platformHost;
    }

    public void setPlatformHost(String platformHost) {
        this.platformHost = platformHost;
    }

    public int getPlatformPort() {
        return platformPort;
    }

    public void setPlatformPort(int platformPort) {
        this.platformPort = platformPort;
    }

    public String getPileCode() {
        return pileCode;
    }

    public void setPileCode(String pileCode) {
        this.pileCode = pileCode;
    }

    public int getGunCount() {
        return gunCount;
    }

    public void setGunCount(int gunCount) {
        this.gunCount = gunCount;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(int protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public String getProgramVersion() {
        return programVersion;
    }

    public void setProgramVersion(String programVersion) {
        this.programVersion = programVersion;
    }

    public String getSimCard() {
        return simCard;
    }

    public void setSimCard(String simCard) {
        this.simCard = simCard;
    }

    public int getNetworkType() {
        return networkType;
    }

    public void setNetworkType(int networkType) {
        this.networkType = networkType;
    }

    public int getOperator() {
        return operator;
    }

    public void setOperator(int operator) {
        this.operator = operator;
    }

    public int getPileType() {
        return pileType;
    }

    public void setPileType(int pileType) {
        this.pileType = pileType;
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

    public int getHeartbeatSeconds() {
        return heartbeatSeconds;
    }

    public void setHeartbeatSeconds(int heartbeatSeconds) {
        this.heartbeatSeconds = heartbeatSeconds;
    }

    public int getIdleRealtimeSeconds() {
        return idleRealtimeSeconds;
    }

    public void setIdleRealtimeSeconds(int idleRealtimeSeconds) {
        this.idleRealtimeSeconds = idleRealtimeSeconds;
    }

    public int getChargingRealtimeSeconds() {
        return chargingRealtimeSeconds;
    }

    public void setChargingRealtimeSeconds(int chargingRealtimeSeconds) {
        this.chargingRealtimeSeconds = chargingRealtimeSeconds;
    }

    public boolean isAutoConnect() {
        return autoConnect;
    }

    public void setAutoConnect(boolean autoConnect) {
        this.autoConnect = autoConnect;
    }

    public int getReconnectSeconds() {
        return reconnectSeconds;
    }

    public void setReconnectSeconds(int reconnectSeconds) {
        this.reconnectSeconds = reconnectSeconds;
    }

    public int getTransactionRetrySeconds() {
        return transactionRetrySeconds;
    }

    public void setTransactionRetrySeconds(int transactionRetrySeconds) {
        this.transactionRetrySeconds = transactionRetrySeconds;
    }

    public int getTransactionMaxRetries() {
        return transactionMaxRetries;
    }

    public void setTransactionMaxRetries(int transactionMaxRetries) {
        this.transactionMaxRetries = transactionMaxRetries;
    }

    public double getRatedVoltage() {
        return ratedVoltage;
    }

    public void setRatedVoltage(double ratedVoltage) {
        this.ratedVoltage = ratedVoltage;
    }

    public double getRatedCurrent() {
        return ratedCurrent;
    }

    public void setRatedCurrent(double ratedCurrent) {
        this.ratedCurrent = ratedCurrent;
    }
}