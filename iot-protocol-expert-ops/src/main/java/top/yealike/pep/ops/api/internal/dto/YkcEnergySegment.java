package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(name = "YkcEnergySegment", description = "云快充分时段电量")
public record YkcEnergySegment(BigDecimal unitPrice, BigDecimal energy, BigDecimal lossEnergy, BigDecimal amount) {}
