package top.yealike.pep.ops.charging.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订单在尖、峰、平、谷四个费率段的电量与费用结果。 */
public class ChargeOrderSegment {
    public Long id;

    public Long orderId;

    public int period;

    public BigDecimal energy = BigDecimal.ZERO;

    public BigDecimal lossEnergy = BigDecimal.ZERO;

    public BigDecimal electricityPrice = BigDecimal.ZERO;

    public BigDecimal servicePrice = BigDecimal.ZERO;

    public BigDecimal electricityFee = BigDecimal.ZERO;

    public BigDecimal serviceFee = BigDecimal.ZERO;

    public BigDecimal totalAmount = BigDecimal.ZERO;

    public String source;

    public LocalDateTime createdAt;
}
