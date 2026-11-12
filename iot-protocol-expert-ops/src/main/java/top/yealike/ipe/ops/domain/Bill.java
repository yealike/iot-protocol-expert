package top.yealike.ipe.ops.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "bill",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_bill_source",
                        columnNames = {"protocol", "source_type", "source_transaction_no"}))
public class Bill {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "order_id")
    public Long orderId;

    @Column(name = "order_no", length = 40)
    public String orderNo;

    @Column(name = "device_id", nullable = false)
    public Long deviceId;

    @Column(name = "station_id")
    public Long stationId;

    @Column(nullable = false, length = 24)
    public String protocol;

    @Column(name = "source_type", nullable = false, length = 32)
    public String sourceType;

    @Column(name = "source_transaction_no", nullable = false, length = 64)
    public String sourceTransactionNo;

    @Column(precision = 16, scale = 4)
    public BigDecimal energy = BigDecimal.ZERO;

    @Column(precision = 16, scale = 4)
    public BigDecimal amount = BigDecimal.ZERO;

    @Column(nullable = false, length = 8)
    public String currency = "CNY";

    @Column(name = "amount_type", nullable = false, length = 24)
    public String amountType;

    @Column(name = "stop_reason")
    public int stopReason;

    @Column(name = "ended_at")
    public LocalDateTime endedAt;

    @Column(name = "received_at")
    public LocalDateTime receivedAt;

    @Column(nullable = false)
    public boolean accepted = true;

    @Column(name = "reject_reason", length = 256)
    public String rejectReason;

    @Column(name = "raw_payload", columnDefinition = "text")
    public String rawPayload;

    public LocalDateTime createdAt;
}
