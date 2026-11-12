package top.yealike.ipe.ops.device.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 设备标签：协议专属字段与静态信息，横向扩展不改主表。 */
@Entity
@Table(name = "device_tag")
public class DeviceTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "device_id", nullable = false)
    public Long deviceId;

    @Column(name = "tag_key", nullable = false, length = 64)
    public String tagKey;

    @Column(name = "tag_value", length = 256)
    public String tagValue;

    @Column(name = "value_type", nullable = false, length = 24)
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
