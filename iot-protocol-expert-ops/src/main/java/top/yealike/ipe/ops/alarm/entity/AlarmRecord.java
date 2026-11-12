package top.yealike.ipe.ops.alarm.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Type;
import top.yealike.ipe.ops.support.JsonbStringType;

/** 告警记录：规则命中后的实例，带确认与关闭状态。 */
@Entity
@Table(name = "alarm_record")
public class AlarmRecord {

    public static final String STATE_OPEN = "OPEN";
    public static final String STATE_ACK = "ACK";
    public static final String STATE_CLOSED = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "rule_id")
    public Long ruleId;

    @Column(name = "device_id", nullable = false)
    public Long deviceId;

    @Column(name = "channel_no", length = 16)
    public String channelNo;

    @Column(nullable = false, length = 16)
    public String level;

    @Column(nullable = false, length = 256)
    public String title;

    @Type(JsonbStringType.class)
    @Column(columnDefinition = "jsonb")
    public String detail;

    @Column(nullable = false, length = 16)
    public String state = STATE_OPEN;

    @Column(name = "first_at", nullable = false)
    public LocalDateTime firstAt;

    @Column(name = "last_at", nullable = false)
    public LocalDateTime lastAt;

    @Column(name = "handled_by", length = 64)
    public String handledBy;

    @Column(name = "handled_at")
    public LocalDateTime handledAt;

    @Column(name = "handling_note", length = 512)
    public String handlingNote;

    public LocalDateTime createdAt;

    public boolean isOpen() {
        return STATE_OPEN.equals(state);
    }
}
