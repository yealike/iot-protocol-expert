package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "BindOcppResult", description = "绑定 OCPP 交易结果")
public record BindOcppResult(int transactionId) {}
