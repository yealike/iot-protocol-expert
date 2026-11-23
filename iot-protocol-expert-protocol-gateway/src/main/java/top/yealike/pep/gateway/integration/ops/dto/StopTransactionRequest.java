package top.yealike.pep.gateway.integration.ops.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** OCPP 停止交易请求。 */
public record StopTransactionRequest(
        String protocolDeviceId, int transactionId, BigDecimal meterStop, LocalDateTime endedAt, String reason) {}
