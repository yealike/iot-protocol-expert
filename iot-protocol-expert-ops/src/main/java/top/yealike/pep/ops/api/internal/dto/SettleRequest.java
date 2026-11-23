package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(name = "SettleRequest", description = "订单结算上报")
public record SettleRequest(
        BigDecimal energy,
        BigDecimal electricityFee,
        BigDecimal serviceFee,
        BigDecimal amount,
        BigDecimal lossEnergy,
        LocalDateTime startTime,
        LocalDateTime endTime,
        int stopReason) {}
