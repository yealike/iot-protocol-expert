package top.yealike.pep.ops.charging.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 运营端订单视图。 */
@Schema(name = "OrderView", description = "充电订单视图")
public record OrderView(
        Long id,
        String orderNo,
        String transactionNo,
        Long deviceId,
        String deviceSn,
        String gunNo,
        String platformGunNo,
        String protocol,
        String status,
        BigDecimal meterStartWh,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Long durationSeconds,
        BigDecimal energy,
        BigDecimal electricityFee,
        BigDecimal serviceFee,
        BigDecimal amount,
        BigDecimal lossEnergy,
        String amountSource,
        String rateModelNo,
        String rateSnapshot,
        int stopReason,
        String failReason,
        LocalDateTime createdAt) {}
