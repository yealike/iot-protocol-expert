package top.yealike.ipe.protocol.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 平台计费模型（48 个半小时时段）。 */
@Entity
@Table(name = "rate_model")
public class RateModelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 8)
    private String modelNo;

    @Column(precision = 12, scale = 5)
    private BigDecimal sharpEnergy = BigDecimal.ZERO;
    @Column(precision = 12, scale = 5)
    private BigDecimal sharpService = BigDecimal.ZERO;
    @Column(precision = 12, scale = 5)
    private BigDecimal peakEnergy = BigDecimal.ZERO;
    @Column(precision = 12, scale = 5)
    private BigDecimal peakService = BigDecimal.ZERO;
    @Column(precision = 12, scale = 5)
    private BigDecimal flatEnergy = BigDecimal.ZERO;
    @Column(precision = 12, scale = 5)
    private BigDecimal flatService = BigDecimal.ZERO;
    @Column(precision = 12, scale = 5)
    private BigDecimal valleyEnergy = BigDecimal.ZERO;
    @Column(precision = 12, scale = 5)
    private BigDecimal valleyService = BigDecimal.ZERO;

    private int lossRatio;
    @Column(nullable = false, length = 128)
    private String segments;
    private boolean active;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getModelNo() {
        return modelNo;
    }

    public void setModelNo(String modelNo) {
        this.modelNo = modelNo;
    }

    public BigDecimal getSharpEnergy() {
        return sharpEnergy;
    }

    public void setSharpEnergy(BigDecimal sharpEnergy) {
        this.sharpEnergy = sharpEnergy;
    }

    public BigDecimal getSharpService() {
        return sharpService;
    }

    public void setSharpService(BigDecimal sharpService) {
        this.sharpService = sharpService;
    }

    public BigDecimal getPeakEnergy() {
        return peakEnergy;
    }

    public void setPeakEnergy(BigDecimal peakEnergy) {
        this.peakEnergy = peakEnergy;
    }

    public BigDecimal getPeakService() {
        return peakService;
    }

    public void setPeakService(BigDecimal peakService) {
        this.peakService = peakService;
    }

    public BigDecimal getFlatEnergy() {
        return flatEnergy;
    }

    public void setFlatEnergy(BigDecimal flatEnergy) {
        this.flatEnergy = flatEnergy;
    }

    public BigDecimal getFlatService() {
        return flatService;
    }

    public void setFlatService(BigDecimal flatService) {
        this.flatService = flatService;
    }

    public BigDecimal getValleyEnergy() {
        return valleyEnergy;
    }

    public void setValleyEnergy(BigDecimal valleyEnergy) {
        this.valleyEnergy = valleyEnergy;
    }

    public BigDecimal getValleyService() {
        return valleyService;
    }

    public void setValleyService(BigDecimal valleyService) {
        this.valleyService = valleyService;
    }

    public int getLossRatio() {
        return lossRatio;
    }

    public void setLossRatio(int lossRatio) {
        this.lossRatio = lossRatio;
    }

    public String getSegments() {
        return segments;
    }

    public void setSegments(String segments) {
        this.segments = segments;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}