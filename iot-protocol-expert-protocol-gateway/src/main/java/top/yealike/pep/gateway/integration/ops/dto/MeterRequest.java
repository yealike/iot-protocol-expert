package top.yealike.pep.gateway.integration.ops.dto;

import java.math.BigDecimal;

/** 订单实时计量更新。 */
public record MeterRequest(BigDecimal energy, BigDecimal amount) {}
