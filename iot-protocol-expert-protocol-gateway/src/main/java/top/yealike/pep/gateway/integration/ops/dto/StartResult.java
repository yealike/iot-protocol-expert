package top.yealike.pep.gateway.integration.ops.dto;

/** 启动充电判定结果。 */
public record StartResult(boolean accepted, String orderNo, String transactionNo, Integer failReason, String message) {}
