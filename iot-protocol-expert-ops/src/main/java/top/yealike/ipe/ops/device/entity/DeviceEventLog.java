package top.yealike.ipe.ops.device.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Type;
import top.yealike.ipe.ops.support.JsonbStringType;

/** 设备事件：物模型 events 段声明的条目在运行期的实例。 */
@Entity
@Table(name = "device_event_log")
public class DeviceEventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "device_id", nullable = false)
    public Long deviceId;

    @Column(name = "channel_no", length = 16)
    public String channelNo;

    @Column(name = "event_id", nullable = false, length = 64)
    public String eventId;

    /** INFO / WARN / MAJOR / CRITICAL。 */
    @Column(nullable = false, length = 16)
    public String level = "INFO";

    @Type(JsonbStringType.class)
    @Column(columnDefinition = "jsonb")
    public String content;

    @Column(name = "occurred_at", nullable = false)
    public LocalDateTime occurredAt;

    public LocalDateTime createdAt;

    public DeviceEventLog() {}

    public DeviceEventLog(
            Long deviceId, String channelNo, String eventId, String level, String content, LocalDateTime occurredAt) {
        this.deviceId = deviceId;
        this.channelNo = channelNo;
        this.eventId = eventId;
        this.level = level == null ? "INFO" : level;
        this.content = content;
        this.occurredAt = occurredAt;
        this.createdAt = LocalDateTime.now();
    }
}
