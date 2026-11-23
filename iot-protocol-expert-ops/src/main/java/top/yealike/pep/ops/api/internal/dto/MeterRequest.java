package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(name = "MeterRequest", description = "订单计量上报")
public record MeterRequest(BigDecimal energy, BigDecimal amount) {}
