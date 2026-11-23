package top.yealike.pep.gateway.integration.ops.dto;

/** 交易记录入账结果。 */
public record BillResult(boolean accepted, String rejectReason, Long billId, String orderNo) {}
