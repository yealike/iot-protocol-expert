package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(name = "BillRequest", description = "云快充账单上报")
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
