package top.yealike.ipe.protocol.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 充电订单。 */
@Entity
@Table(name = "charge_order", indexes = {
        @Index(name = "idx_order_pile", columnList = "pileCode"),
        @Index(name = "idx_order_tx", columnList = "transactionNo")
})
public class ChargeOrderEntity {

    public enum Status {
        PENDING, CHARGING, STOPPED, SETTLED, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String transactionNo;
    @Column(nullable = false, length = 32)
    private String pileCode;
    @Column(nullable = false, length = 4)
    private String gunNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    private int startType;
    @Column(length = 32)
    private String physicalCard;
    @Column(length = 32)
    private String logicalCard;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    @Column(precision = 16, scale = 4)
    private BigDecimal energy = BigDecimal.ZERO;
    @Column(precision = 16, scale = 4)
    private BigDecimal amount = BigDecimal.ZERO;

    private int stopReason;
    @Column(length = 128)
    private String failReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTransactionNo() {
        return transactionNo;
    }

    public void setTransactionNo(String transactionNo) {
        this.transactionNo = transactionNo;
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

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int getStartType() {
        return startType;
    }

    public void setStartType(int startType) {
        this.startType = startType;
    }

    public String getPhysicalCard() {
        return physicalCard;
    }

    public void setPhysicalCard(String physicalCard) {
        this.physicalCard = physicalCard;
    }

    public String getLogicalCard() {
        return logicalCard;
    }

    public void setLogicalCard(String logicalCard) {
        this.logicalCard = logicalCard;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
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

    public int getStopReason() {
        return stopReason;
    }

    public void setStopReason(int stopReason) {
        this.stopReason = stopReason;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}