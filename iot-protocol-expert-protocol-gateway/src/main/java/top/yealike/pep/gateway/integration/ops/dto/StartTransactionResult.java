package top.yealike.pep.gateway.integration.ops.dto;

/** OCPP 启动交易结果。 */
public record StartTransactionResult(
        boolean accepted, String orderNo, Integer transactionId, String errorCode, String message) {}
