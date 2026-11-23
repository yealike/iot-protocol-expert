package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "FailRequest", description = "订单失败上报")
public record FailRequest(String reason) {}
