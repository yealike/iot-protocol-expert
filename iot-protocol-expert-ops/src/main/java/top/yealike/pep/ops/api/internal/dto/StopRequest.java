package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "StopRequest", description = "订单停止请求")
public record StopRequest(int stopReason) {}
