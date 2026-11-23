package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(name = "MeterValuesRequest", description = "OCPP 电表采样上报")
public record MeterValuesRequest(
        String protocolDeviceId,
        int connectorId,
        Integer transactionId,
        BigDecimal energyWh,
        BigDecimal voltage,
        BigDecimal current,
        Integer soc,
        BigDecimal powerKw,
        BigDecimal temperature,
        String sampleTime) {}
