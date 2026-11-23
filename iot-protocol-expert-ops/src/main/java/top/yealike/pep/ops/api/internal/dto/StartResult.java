package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "StartResult", description = "启动充电判定结果")
public record StartResult(boolean accepted, String orderNo, String transactionNo, Integer failReason, String message) {}
