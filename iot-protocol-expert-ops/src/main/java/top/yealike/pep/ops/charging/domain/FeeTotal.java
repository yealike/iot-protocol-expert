package top.yealike.pep.ops.charging.domain;

import java.math.BigDecimal;

public record FeeTotal(
        BigDecimal energy,
        BigDecimal electricityFee,
        BigDecimal serviceFee,
        BigDecimal amount,
        BigDecimal lossEnergy,
        boolean partial,
        String breakdown) {}
