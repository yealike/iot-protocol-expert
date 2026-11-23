package top.yealike.pep.gateway.integration.ops.dto;

import java.math.BigDecimal;

/** 云快充交易记录的分段电量与金额。 */
public record YkcEnergySegment(BigDecimal unitPrice, BigDecimal energy, BigDecimal lossEnergy, BigDecimal amount) {}
