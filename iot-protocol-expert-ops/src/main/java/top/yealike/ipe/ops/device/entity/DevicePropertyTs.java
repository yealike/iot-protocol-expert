package top.yealike.ipe.ops.device.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Type;
import top.yealike.ipe.ops.support.JsonbStringType;

/**
 * 属性时序。
 *
 * <p>当前直接落 PostgreSQL。规模上来后把写入与查询收敛到 {@code StorageStrategy} 接口，换 TDengine / TimescaleDB 时不改业务代码。
 */
@Entity
@Table(name = "device_property_ts")
public class DevicePropertyTs {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "device_id", nullable = false)
    public Long deviceId;

    @Column(name = "channel_no", nullable = false, length = 16)
    public String channelNo = "";

    @Column(name = "property_id", nullable = false, length = 64)
    public String propertyId;

    @Column(name = "value_num", precision = 20, scale = 6)
    public BigDecimal valueNum;

    @Column(name = "value_text", length = 512)
    public String valueText;

    @Type(JsonbStringType.class)
    @Column(name = "value_json", columnDefinition = "jsonb")
    public String valueJson;

    @Column(name = "collected_at", nullable = false)
    public LocalDateTime collectedAt;

    public LocalDateTime createdAt;

    public DevicePropertyTs() {}

    public DevicePropertyTs(
            Long deviceId,
            String channelNo,
            String propertyId,
            BigDecimal valueNum,
            String valueText,
            String valueJson,
            LocalDateTime collectedAt) {
        this.deviceId = deviceId;
        this.channelNo = channelNo == null ? "" : channelNo;
        this.propertyId = propertyId;
        this.valueNum = valueNum;
        this.valueText = valueText;
        this.valueJson = valueJson;
        this.collectedAt = collectedAt;
        this.createdAt = LocalDateTime.now();
    }
}
