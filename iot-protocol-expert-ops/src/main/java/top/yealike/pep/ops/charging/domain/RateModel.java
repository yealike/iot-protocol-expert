package top.yealike.pep.ops.charging.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class RateModel {
    public Long id;

    public String modelNo;

    public BigDecimal sharpEnergy = BigDecimal.ZERO;

    public BigDecimal sharpService = BigDecimal.ZERO;

    public BigDecimal peakEnergy = BigDecimal.ZERO;

    public BigDecimal peakService = BigDecimal.ZERO;

    public BigDecimal flatEnergy = BigDecimal.ZERO;

    public BigDecimal flatService = BigDecimal.ZERO;

    public BigDecimal valleyEnergy = BigDecimal.ZERO;

    public BigDecimal valleyService = BigDecimal.ZERO;

    public int lossRatio;

    public String segments;

    public boolean active;
    public LocalDateTime updatedAt;
}
