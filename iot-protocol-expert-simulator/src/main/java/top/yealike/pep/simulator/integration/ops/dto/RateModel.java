package top.yealike.pep.simulator.integration.ops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/** 运维平台下发的只读费率模型。 */
@Schema(name = "SimulatorRateModel", description = "运维平台下发的只读费率模型")
public record RateModel(
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
        boolean active) {}
