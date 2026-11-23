package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(name = "StopTransactionRequest", description = "OCPP 结束交易请求")
public record StopTransactionRequest(
        String protocolDeviceId, int transactionId, BigDecimal meterStop, LocalDateTime endedAt, String reason) {}
