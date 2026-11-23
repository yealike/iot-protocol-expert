package top.yealike.pep.gateway.integration.ops.dto;

import java.math.BigDecimal;

/** OCPP 交易号绑定请求。 */
public record BindOcppRequest(BigDecimal meterStartWh) {}
