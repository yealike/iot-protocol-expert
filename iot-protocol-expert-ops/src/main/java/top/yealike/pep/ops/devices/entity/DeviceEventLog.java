package top.yealike.pep.ops.devices.entity;

import java.time.LocalDateTime;

/** 设备事件：物模型 events 段声明的条目在运行期的实例。 */
public class DeviceEventLog {

    public Long id;

    public Long deviceId;

    public String channelNo;

    public String eventId;

    /** INFO / WARN / MAJOR / CRITICAL。 */
    public String level = "INFO";

    public String content;

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
