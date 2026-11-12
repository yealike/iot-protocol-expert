package top.yealike.ipe.ops.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "charge_order")
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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "order_no", nullable = false, unique = true, length = 40)
    public String orderNo;

    @Column(name = "device_id", nullable = false)
    public Long deviceId;

    @Column(name = "gun_no", nullable = false, length = 4)
    public String gunNo;

    @Column(name = "station_id")
    public Long stationId;

    @Column(nullable = false, length = 24)
    public String protocol;

    @Column(name = "protocol_transaction_id", length = 64)
    public String protocolTransactionId;

    @Column(name = "ocpp_transaction_id")
    public Integer ocppTransactionId;

    @Column(name = "meter_start_wh", precision = 20, scale = 3)
    public BigDecimal meterStartWh;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status = Status.PENDING;

    @Column(name = "start_type", nullable = false)
    public int startType;

    @Column(name = "physical_card", length = 32)
    public String physicalCard;

    @Column(name = "logical_card", length = 32)
    public String logicalCard;

    @Column(name = "start_time")
    public LocalDateTime startTime;

    @Column(name = "end_time")
    public LocalDateTime endTime;

    @Column(precision = 16, scale = 4)
    public BigDecimal energy = BigDecimal.ZERO;

    @Column(precision = 16, scale = 4)
    public BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "amount_source", nullable = false, length = 24)
    public String amountSource = AMOUNT_SOURCE_ESTIMATE;

    @Column(name = "stop_reason")
    public int stopReason;

    @Column(name = "fail_reason", length = 256)
    public String failReason;

    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
