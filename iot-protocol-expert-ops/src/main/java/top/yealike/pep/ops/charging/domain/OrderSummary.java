package top.yealike.pep.ops.charging.domain;

import java.math.BigDecimal;

public record OrderSummary(long c, BigDecimal e, BigDecimal a) {
    public Long getC() {
        return c;
    }

    public BigDecimal getE() {
        return e;
    }

    public BigDecimal getA() {
        return a;
    }
}
