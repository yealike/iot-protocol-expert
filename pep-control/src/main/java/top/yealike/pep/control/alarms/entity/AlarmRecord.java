package top.yealike.pep.control.alarms.entity;

import java.time.LocalDateTime;

/** 告警记录：规则命中后的实例，带确认与关闭状态。 */
public class AlarmRecord {

    public static final String STATE_OPEN = "OPEN";
    public static final String STATE_ACK = "ACK";
    public static final String STATE_CLOSED = "CLOSED";

    public Long id;

    public Long ruleId;

    public Long deviceId;

    public String channelNo;

    public String level;

    public String title;

    public String detail;

    public String state = STATE_OPEN;

    public LocalDateTime firstAt;

    public LocalDateTime lastAt;

    public String handledBy;

    public LocalDateTime handledAt;

    public String handlingNote;

    public LocalDateTime createdAt;

    public boolean isOpen() {
        return STATE_OPEN.equals(state);
    }
}
