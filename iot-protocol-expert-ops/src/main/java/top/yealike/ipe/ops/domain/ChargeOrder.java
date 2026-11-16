package top.yealike.ipe.ops.domain;

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

    public String gunNo;

    public Long stationId;

    public String protocol;

    public String protocolTransactionId;

    public Integer ocppTransactionId;

    public BigDecimal meterStartWh;

    public Status status = Status.PENDING;

    public int startType;

    public String physicalCard;

    public String logicalCard;

    public LocalDateTime startTime;

    public LocalDateTime endTime;

    public BigDecimal energy = BigDecimal.ZERO;

    public BigDecimal amount = BigDecimal.ZERO;

    public String amountSource = AMOUNT_SOURCE_ESTIMATE;

    public int stopReason;

    public String failReason;

    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
