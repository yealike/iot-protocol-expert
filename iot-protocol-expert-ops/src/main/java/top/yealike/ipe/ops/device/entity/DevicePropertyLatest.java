package top.yealike.ipe.ops.device.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Type;
import top.yealike.ipe.ops.support.JsonbStringType;

/**
 * 属性最新值：一个属性一行，按值类型选列。
 *
 * <p>唯一键 {@code (device_id, channel_no, property_id)}，无通道属性用空串占位。 查询走 {@code value_num}（数值）或 {@code
 * value_text}（枚举/字符串），复合结构在 {@code value_json} 里，不参与 SQL 过滤。
 */
@Entity
@Table(name = "device_property_latest")
public class DevicePropertyLatest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "device_id", nullable = false)
    public Long deviceId;

    @Column(name = "channel_no", nullable = false, length = 16)
    public String channelNo = "";

    @Column(name = "property_id", nullable = false, length = 64)
    public String propertyId;

    @Column(name = "value_type", nullable = false, length = 24)
    public String valueType;

    @Column(name = "value_num", precision = 20, scale = 6)
    public BigDecimal valueNum;

    @Column(name = "value_text", length = 512)
    public String valueText;

    @Type(JsonbStringType.class)
    @Column(name = "value_json", columnDefinition = "jsonb")
    public String valueJson;

    @Column(name = "value_time")
    public LocalDateTime valueTime;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    public DevicePropertyLatest() {}

    public DevicePropertyLatest(Long deviceId, String channelNo, String propertyId, String valueType) {
        this.deviceId = deviceId;
        this.channelNo = channelNo == null ? "" : channelNo;
        this.propertyId = propertyId;
        this.valueType = valueType;
    }
}
