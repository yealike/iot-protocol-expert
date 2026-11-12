package top.yealike.ipe.ops.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "rate_model")
public class RateModel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "model_no", nullable = false, unique = true, length = 8)
    public String modelNo;

    @Column(name = "sharp_energy")
    public BigDecimal sharpEnergy = BigDecimal.ZERO;

    @Column(name = "sharp_service")
    public BigDecimal sharpService = BigDecimal.ZERO;

    @Column(name = "peak_energy")
    public BigDecimal peakEnergy = BigDecimal.ZERO;

    @Column(name = "peak_service")
    public BigDecimal peakService = BigDecimal.ZERO;

    @Column(name = "flat_energy")
    public BigDecimal flatEnergy = BigDecimal.ZERO;

    @Column(name = "flat_service")
    public BigDecimal flatService = BigDecimal.ZERO;

    @Column(name = "valley_energy")
    public BigDecimal valleyEnergy = BigDecimal.ZERO;

    @Column(name = "valley_service")
    public BigDecimal valleyService = BigDecimal.ZERO;

    @Column(name = "loss_ratio")
    public int lossRatio;

    @Column(nullable = false, length = 128)
    public String segments;

    public boolean active;
    public LocalDateTime updatedAt;
}
