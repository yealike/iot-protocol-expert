package top.yealike.pep.ops.devices.entity;

import java.time.LocalDateTime;

/** 设备标签：协议专属字段与静态信息，横向扩展不改主表。 */
public class DeviceTag {

    public Long id;

    public Long deviceId;

    public String tagKey;

    public String tagValue;

    public String valueType = "string";

    public LocalDateTime updatedAt;

    public DeviceTag() {}

    public DeviceTag(Long deviceId, String tagKey, String tagValue, String valueType) {
        this.deviceId = deviceId;
        this.tagKey = tagKey;
        this.tagValue = tagValue;
        this.valueType = valueType == null ? "string" : valueType;
        this.updatedAt = LocalDateTime.now();
    }
}
