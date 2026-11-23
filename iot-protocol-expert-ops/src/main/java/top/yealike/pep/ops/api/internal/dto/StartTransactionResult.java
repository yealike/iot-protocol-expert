package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "StartTransactionResult", description = "OCPP 开始交易结果")
public record StartTransactionResult(
        boolean accepted, String orderNo, Integer transactionId, String errorCode, String message) {}
