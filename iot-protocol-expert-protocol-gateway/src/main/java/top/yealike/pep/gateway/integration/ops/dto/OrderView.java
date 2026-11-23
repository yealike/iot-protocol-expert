package top.yealike.pep.gateway.integration.ops.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 运维平台订单视图。 */
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
        BigDecimal energy,
        BigDecimal electricityFee,
        BigDecimal serviceFee,
        BigDecimal amount,
        BigDecimal lossEnergy,
        String amountSource,
        String rateModelNo,
        int stopReason,
        String failReason,
        LocalDateTime createdAt) {}
