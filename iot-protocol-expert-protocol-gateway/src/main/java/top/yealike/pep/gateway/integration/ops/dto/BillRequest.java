package top.yealike.pep.gateway.integration.ops.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 云快充交易记录入账请求。 */
public record BillRequest(
        String protocol,
        String protocolDeviceId,
        String transactionNo,
        String gunNo,
        LocalDateTime startTime,
        LocalDateTime endTime,
        LocalDateTime tradeTime,
        BigDecimal totalEnergy,
        BigDecimal totalAmount,
        BigDecimal lossTotalEnergy,
        List<YkcEnergySegment> segments,
        int stopReason,
        int tradeFlag,
        String physicalCard,
        String vin,
        String rawHex) {}
