package top.yealike.pep.ops.charging.domain;

import java.math.BigDecimal;

public record SegmentRate(int period, BigDecimal electricityPrice, BigDecimal servicePrice) {}
