package top.yealike.pep.ops.charging.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 费率模型视图与保存请求。 */
@Schema(name = "RateModelView", description = "费率模型")
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
