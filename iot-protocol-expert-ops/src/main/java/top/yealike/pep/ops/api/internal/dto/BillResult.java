package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "BillResult", description = "账单受理结果")
public record BillResult(boolean accepted, String rejectReason, Long billId, String orderNo) {}
