package top.yealike.pep.gateway.integration.ops.dto;

import java.math.BigDecimal;

/** OCPP 停止交易结果。 */
public record StopTransactionResult(
        boolean accepted, String orderNo, BigDecimal energy, BigDecimal amount, String message) {}
