package top.yealike.ipe.ops.device.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Type;
import top.yealike.ipe.ops.support.JsonbStringType;

/**
 * 通道：充电枪、光伏组串、储能电池簇、摄像机镜头、电表相。
 *
 * <p>通道是显式实体，不是属性名前缀。这样「02 号枪的 SOC」是一个明确的二维键 (channelNo, propertyId)，前端可以按通道分组渲染，3D 可以按通道定位。
 */
@Entity
@Table(name = "device_channel")
public class DeviceChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "device_id", nullable = false)
    public Long deviceId;

    @Column(name = "channel_no", nullable = false, length = 16)
    public String channelNo;

    /** CONNECTOR / STRING / PACK / LENS / PHASE。 */
    @Column(name = "channel_type", nullable = false, length = 32)
    public String channelType;

    @Column(length = 64)
    public String name;

    @Column(nullable = false)
    public boolean enabled = true;

    @Type(JsonbStringType.class)
    @Column(columnDefinition = "jsonb")
    public String metadata;

    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
