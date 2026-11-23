package top.yealike.pep.ops.charging.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 运营端账单视图。 */
@Schema(name = "BillView", description = "充电账单视图")
public record BillView(
        Long id,
        Long orderId,
        String orderNo,
        Long deviceId,
        String deviceSn,
        String protocol,
        String sourceType,
        String sourceTransactionNo,
        BigDecimal energy,
        BigDecimal electricityFee,
        BigDecimal serviceFee,
        BigDecimal amount,
        BigDecimal lossEnergy,
        String rateModelNo,
        boolean partial,
        String currency,
        String amountType,
        int stopReason,
        LocalDateTime endedAt,
        LocalDateTime receivedAt,
        boolean accepted,
        String rejectReason,
        LocalDateTime createdAt) {}
