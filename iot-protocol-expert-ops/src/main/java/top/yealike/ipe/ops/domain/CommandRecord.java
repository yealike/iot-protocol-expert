package top.yealike.ipe.ops.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "command_record")
public class CommandRecord {
    public enum Status {
        PENDING,
        SENT,
        ACKED,
        WAITING_STATE,
        SUCCESS,
        REJECTED,
        TIMEOUT,
        UNKNOWN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "device_id", nullable = false)
    public Long deviceId;

    @Column(nullable = false, length = 24)
    public String protocol;

    @Column(name = "command_type", nullable = false, length = 64)
    public String commandType;

    @Column(name = "request_payload", columnDefinition = "text")
    public String requestPayload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    public Status status = Status.PENDING;

    @Column(name = "sent_at")
    public LocalDateTime sentAt;

    @Column(name = "ack_at")
    public LocalDateTime ackAt;

    @Column(name = "response_payload", columnDefinition = "text")
    public String responsePayload;

    @Column(length = 64)
    public String operator;

    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
