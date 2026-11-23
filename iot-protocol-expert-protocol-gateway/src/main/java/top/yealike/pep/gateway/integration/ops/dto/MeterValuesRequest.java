package top.yealike.pep.gateway.integration.ops.dto;

import java.math.BigDecimal;

/** OCPP 电表值上报。 */
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
