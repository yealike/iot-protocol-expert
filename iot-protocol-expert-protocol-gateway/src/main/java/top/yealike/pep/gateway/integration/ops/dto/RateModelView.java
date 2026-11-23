package top.yealike.pep.gateway.integration.ops.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 生效中的费率模型。 */
public record RateModelView(
        String modelNo,
        BigDecimal sharpEnergy,
        BigDecimal sharpService,
        BigDecimal peakEnergy,
        BigDecimal peakService,
        BigDecimal flatEnergy,
        BigDecimal flatService,
        BigDecimal valleyEnergy,
        BigDecimal valleyService,
        int lossRatio,
        List<Integer> segments,
        boolean active,
        LocalDateTime updatedAt) {}
