package top.yealike.ipe.protocol.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 桩上送并结算的交易记录。 */
@Entity
@Table(name = "transaction_record", indexes = @Index(name = "idx_tx_pile", columnList = "pileCode"))
public class TransactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String transactionNo;
    @Column(nullable = false, length = 32)
    private String pileCode;
    @Column(nullable = false, length = 4)
    private String gunNo;

    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime tradeTime;

    @Column(precision = 16, scale = 4)
    private BigDecimal totalEnergy = BigDecimal.ZERO;
    @Column(precision = 16, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;
    @Column(precision = 16, scale = 4)
    private BigDecimal lossTotalEnergy = BigDecimal.ZERO;

    private int stopReason;
    private int tradeFlag;
    @Column(length = 32)
    private String physicalCard;
    @Column(length = 32)
    private String vin;

    private boolean accepted;
    @Column(length = 128)
    private String rejectReason;

    @Lob
    @Column(length = 4096)
    private String rawHex;

    private LocalDateTime receivedAt;

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

    public LocalDateTime getTradeTime() {
        return tradeTime;
    }

    public void setTradeTime(LocalDateTime tradeTime) {
        this.tradeTime = tradeTime;
    }

    public BigDecimal getTotalEnergy() {
        return totalEnergy;
    }

    public void setTotalEnergy(BigDecimal totalEnergy) {
        this.totalEnergy = totalEnergy;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public BigDecimal getLossTotalEnergy() {
        return lossTotalEnergy;
    }

    public void setLossTotalEnergy(BigDecimal lossTotalEnergy) {
        this.lossTotalEnergy = lossTotalEnergy;
    }

    public int getStopReason() {
        return stopReason;
    }

    public void setStopReason(int stopReason) {
        this.stopReason = stopReason;
    }

    public int getTradeFlag() {
        return tradeFlag;
    }

    public void setTradeFlag(int tradeFlag) {
        this.tradeFlag = tradeFlag;
    }

    public String getPhysicalCard() {
        return physicalCard;
    }

    public void setPhysicalCard(String physicalCard) {
        this.physicalCard = physicalCard;
    }

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public void setAccepted(boolean accepted) {
        this.accepted = accepted;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public String getRawHex() {
        return rawHex;
    }

    public void setRawHex(String rawHex) {
        this.rawHex = rawHex;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }
}