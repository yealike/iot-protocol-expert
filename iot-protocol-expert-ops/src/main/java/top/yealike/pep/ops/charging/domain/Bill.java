package top.yealike.pep.ops.charging.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Bill {
    public Long id;

    public Long orderId;

    public String orderNo;

    public Long deviceId;

    public String protocol;

    public String sourceType;

    public String sourceTransactionNo;

    public BigDecimal energy = BigDecimal.ZERO;

    public BigDecimal electricityFee;

    public BigDecimal serviceFee;

    public BigDecimal amount = BigDecimal.ZERO;

    public BigDecimal lossEnergy;

    public String rateModelNo;

    public String rateSnapshot;

    public String feeBreakdown;

    public boolean partial;

    public String currency = "CNY";

    public String amountType;

    public int stopReason;

    public LocalDateTime endedAt;

    public LocalDateTime receivedAt;

    public boolean accepted = true;

    public String rejectReason;

    public String rawPayload;

    public LocalDateTime createdAt;
}
