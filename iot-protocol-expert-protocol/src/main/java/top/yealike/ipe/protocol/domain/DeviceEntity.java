package top.yealike.ipe.protocol.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 充电桩档案与在线状态。 */
@Entity
@Table(name = "device")
public class DeviceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String pileCode;

    private int pileType;
    private int gunCount;
    private int protocolVersion;
    @Column(length = 32)
    private String programVersion;
    private int networkType;
    @Column(length = 32)
    private String simCard;
    private int operator;

    private boolean online;
    private LocalDateTime lastSeen;
    private LocalDateTime registeredAt;
    private LocalDateTime onlineAt;

    private boolean allowWork = true;
    private int maxPowerPercent = 100;
    @Column(length = 128)
    private String stationName;
    @Column(length = 256)
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPileCode() {
        return pileCode;
    }

    public void setPileCode(String pileCode) {
        this.pileCode = pileCode;
    }

    public int getPileType() {
        return pileType;
    }

    public void setPileType(int pileType) {
        this.pileType = pileType;
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

    public int getNetworkType() {
        return networkType;
    }

    public void setNetworkType(int networkType) {
        this.networkType = networkType;
    }

    public String getSimCard() {
        return simCard;
    }

    public void setSimCard(String simCard) {
        this.simCard = simCard;
    }

    public int getOperator() {
        return operator;
    }

    public void setOperator(int operator) {
        this.operator = operator;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public LocalDateTime getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(LocalDateTime lastSeen) {
        this.lastSeen = lastSeen;
    }

    public LocalDateTime getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(LocalDateTime registeredAt) {
        this.registeredAt = registeredAt;
    }

    public LocalDateTime getOnlineAt() {
        return onlineAt;
    }

    public void setOnlineAt(LocalDateTime onlineAt) {
        this.onlineAt = onlineAt;
    }

    public boolean isAllowWork() {
        return allowWork;
    }

    public void setAllowWork(boolean allowWork) {
        this.allowWork = allowWork;
    }

    public int getMaxPowerPercent() {
        return maxPowerPercent;
    }

    public void setMaxPowerPercent(int maxPowerPercent) {
        this.maxPowerPercent = maxPowerPercent;
    }

    public String getStationName() {
        return stationName;
    }

    public void setStationName(String stationName) {
        this.stationName = stationName;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}