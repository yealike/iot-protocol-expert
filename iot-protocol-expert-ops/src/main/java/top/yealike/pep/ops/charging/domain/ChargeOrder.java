package top.yealike.pep.ops.charging.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class ChargeOrder {
    public enum Status {
        PENDING,
        CHARGING,
        STOPPED,
        SETTLED,
        FAILED
    }

    public static final String AMOUNT_SOURCE_DEVICE = "DEVICE_BILL";
    public static final String AMOUNT_SOURCE_ESTIMATE = "PLATFORM_ESTIMATE";

    public Long id;

    public String orderNo;

    public Long deviceId;

    public String deviceSn;

    public String gunNo;

    public String platformGunNo;

    public String protocol;

    public BigDecimal meterStartWh;

    public Status status = Status.PENDING;

    public int startType;

    public String physicalCard;

    public String logicalCard;

    public LocalDateTime startTime;

    public LocalDateTime endTime;

    public BigDecimal energy = BigDecimal.ZERO;

    public BigDecimal electricityFee;

    public BigDecimal serviceFee;

    public BigDecimal amount = BigDecimal.ZERO;

    public BigDecimal lossEnergy;

    public String rateModelNo;

    public String rateSnapshot;

    public String feeBreakdown;

    public String amountSource = AMOUNT_SOURCE_ESTIMATE;

    public int stopReason;

    public String failReason;

    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
