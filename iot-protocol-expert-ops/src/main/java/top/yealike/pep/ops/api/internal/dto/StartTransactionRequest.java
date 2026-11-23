package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(name = "StartTransactionRequest", description = "OCPP 开始交易请求")
public record StartTransactionRequest(
        String protocolDeviceId, int connectorId, String idTag, BigDecimal meterStart, LocalDateTime startedAt) {}
