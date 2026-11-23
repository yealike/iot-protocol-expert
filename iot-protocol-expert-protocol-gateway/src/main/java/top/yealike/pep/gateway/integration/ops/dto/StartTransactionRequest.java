package top.yealike.pep.gateway.integration.ops.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** OCPP 启动交易请求。 */
public record StartTransactionRequest(
        String protocolDeviceId, int connectorId, String idTag, BigDecimal meterStart, LocalDateTime startedAt) {}
