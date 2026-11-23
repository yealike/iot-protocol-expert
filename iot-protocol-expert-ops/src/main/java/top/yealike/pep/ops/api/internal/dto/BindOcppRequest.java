package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(name = "BindOcppRequest", description = "绑定 OCPP 交易请求")
public record BindOcppRequest(BigDecimal meterStartWh) {}
