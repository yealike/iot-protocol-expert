package top.yealike.ipe.protocol.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 每枪最新实时状态（覆盖式）。 */
@Entity
@Table(name = "gun_state", indexes = @Index(name = "idx_gun_state_pile", columnList = "pileCode"))
public class GunStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String pileCode;
    @Column(nullable = false, length = 4)
    private String gunNo;

    private int state;
    private int gunHomed;
    private int plugged;
    @Column(precision = 12, scale = 2)
    private BigDecimal voltage = BigDecimal.ZERO;
    @Column(precision = 12, scale = 2)
    private BigDecimal current = BigDecimal.ZERO;
    private int gunLineTemp;
    private int soc;
    private int batteryTemp;
    private int totalMinutes;
    private int remainMinutes;
    @Column(precision = 16, scale = 4)
    private BigDecimal energy = BigDecimal.ZERO;
    @Column(precision = 16, scale = 4)
    private BigDecimal amount = BigDecimal.ZERO;
    private int hardwareFault;
    @Column(length = 40)
    private String transactionNo;
    private LocalDateTime updatedAt;

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

    public String getGunNo() {
        return gunNo;
    }

    public void setGunNo(String gunNo) {
        this.gunNo = gunNo;
    }

    public int getState() {
        return state;
    }

    public void setState(int state) {
        this.state = state;
    }

    public int getGunHomed() {
        return gunHomed;
    }

    public void setGunHomed(int gunHomed) {
        this.gunHomed = gunHomed;
    }

    public int getPlugged() {
        return plugged;
    }

    public void setPlugged(int plugged) {
        this.plugged = plugged;
    }

    public BigDecimal getVoltage() {
        return voltage;
    }

    public void setVoltage(BigDecimal voltage) {
        this.voltage = voltage;
    }

    public BigDecimal getCurrent() {
        return current;
    }

    public void setCurrent(BigDecimal current) {
        this.current = current;
    }

    public int getGunLineTemp() {
        return gunLineTemp;
    }

    public void setGunLineTemp(int gunLineTemp) {
        this.gunLineTemp = gunLineTemp;
    }

    public int getSoc() {
        return soc;
    }

    public void setSoc(int soc) {
        this.soc = soc;
    }

    public int getBatteryTemp() {
        return batteryTemp;
    }

    public void setBatteryTemp(int batteryTemp) {
        this.batteryTemp = batteryTemp;
    }

    public int getTotalMinutes() {
        return totalMinutes;
    }

    public void setTotalMinutes(int totalMinutes) {
        this.totalMinutes = totalMinutes;
    }

    public int getRemainMinutes() {
        return remainMinutes;
    }

    public void setRemainMinutes(int remainMinutes) {
        this.remainMinutes = remainMinutes;
    }

    public BigDecimal getEnergy() {
        return energy;
    }

    public void setEnergy(BigDecimal energy) {
        this.energy = energy;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public int getHardwareFault() {
        return hardwareFault;
    }

    public void setHardwareFault(int hardwareFault) {
        this.hardwareFault = hardwareFault;
    }

    public String getTransactionNo() {
        return transactionNo;
    }

    public void setTransactionNo(String transactionNo) {
        this.transactionNo = transactionNo;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}