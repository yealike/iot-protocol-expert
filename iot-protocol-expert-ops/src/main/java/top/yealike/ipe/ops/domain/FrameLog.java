package top.yealike.ipe.ops.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "frame_log")
public class FrameLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "device_id")
    public Long deviceId;

    @Column(nullable = false, length = 24)
    public String protocol;

    @Column(name = "protocol_device_id", length = 64)
    public String protocolDeviceId;

    @Column(nullable = false, length = 8)
    public String direction;

    @Column(name = "frame_type")
    public int frameType;

    @Column(name = "frame_label", length = 64)
    public String frameLabel;

    @Column(name = "message_id", length = 64)
    public String messageId;

    @Column(name = "sequence_no")
    public int sequenceNo;

    public boolean encrypted;

    @Column(name = "body_hex", length = 8192)
    public String bodyHex;

    @Column(name = "wire_hex", length = 8192)
    public String wireHex;

    @Column(length = 512)
    public String summary;

    @Column(name = "error_message", length = 512)
    public String errorMessage;

    @Column(name = "trace_id", length = 64)
    public String traceId;

    public LocalDateTime createdAt;
}
