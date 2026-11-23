package top.yealike.pep.simulator.runtime;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import top.yealike.pep.codec.frames.OperationFrames;
import top.yealike.pep.simulator.domain.GunState;

/** 单把模拟枪的运行时状态；模拟器内部直接读写字段。 */
public final class Gun {
    final String gunNo;
    final String platformGunNo;
    final String protocolConnectorNo;
    boolean plugged = false;
    String phase = "待机";
    ChargingLedger ledger;
    LocalDateTime lastAdvance;
    BigDecimal meter = new BigDecimal("1000.0000");
    BigDecimal meterStart = meter;
    String physicalCard = "00000000D14B0A54";
    BigDecimal balance = new BigDecimal("1000");
    int startType = 1;
    int initialSoc = 20;
    double capacityKwh = 60;
    double socValue = 20;
    String vin = "LSVAA2A40NX000001";
    String parallelNo;
    int mainGun;
    long lastBmsAt;
    long lastLockAt;
    long authDeadline;
    double targetEnergy;
    double targetAmount;
    int targetMinutes;
    int bmsErrorReason;
    int lockStatus = 255;
    OperationFrames.RemoteStart waitingStart;
    Integer ocppTransactionId;
    BigDecimal ocppMeterStartWh = BigDecimal.ZERO;
    int waitingSequence;
    long waitingUntil;
    boolean fault;
    int faultBits;
    String ocppFaultCode = "NoError";
    boolean allowWork = true;
    GunState state = GunState.IDLE;
    String transactionNo;
    LocalDateTime startTime;
    BigDecimal energy = BigDecimal.ZERO;
    BigDecimal amount = BigDecimal.ZERO;
    int soc = 20;
    BigDecimal voltage = BigDecimal.ZERO;
    BigDecimal current = BigDecimal.ZERO;
    int totalMinutes;
    int remainMinutes;
    long lastRealtimeAt;
    long lastHeartbeatAt;
    long heartbeatAckAt;

    Gun(String gunNo, String platformGunNo, String protocolConnectorNo) {
        this.gunNo = gunNo;
        this.platformGunNo = platformGunNo;
        this.protocolConnectorNo = protocolConnectorNo;
    }

    public String getGunNo() {
        return gunNo;
    }

    public String getPlatformGunNo() {
        return platformGunNo;
    }

    public String getProtocolConnectorNo() {
        return protocolConnectorNo;
    }

    public boolean isPlugged() {
        return plugged;
    }

    public boolean isFault() {
        return fault;
    }

    public boolean isAllowWork() {
        return allowWork;
    }

    public GunState getState() {
        return state;
    }

    public String getTransactionNo() {
        return transactionNo;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public BigDecimal getEnergy() {
        return energy;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public int getSoc() {
        return soc;
    }

    public BigDecimal getVoltage() {
        return voltage;
    }

    public BigDecimal getCurrent() {
        return current;
    }

    public int getTotalMinutes() {
        return totalMinutes;
    }

    public int getRemainMinutes() {
        return remainMinutes;
    }
}
