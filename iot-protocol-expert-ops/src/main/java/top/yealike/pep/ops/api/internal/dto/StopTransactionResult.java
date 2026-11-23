package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(name = "StopTransactionResult", description = "OCPP 结束交易结果")
public record StopTransactionResult(
        boolean accepted,
        String orderNo,
        BigDecimal energy,
        BigDecimal electricityFee,
        BigDecimal serviceFee,
        BigDecimal amount,
        String message) {}
