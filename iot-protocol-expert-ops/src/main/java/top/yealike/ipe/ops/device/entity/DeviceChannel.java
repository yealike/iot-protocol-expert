package top.yealike.ipe.ops.device.entity;

import java.time.LocalDateTime;

/**
 * 通道：充电枪、光伏组串、储能电池簇、摄像机镜头、电表相。
 *
 * <p>通道是显式实体，不是属性名前缀。这样「02 号枪的 SOC」是一个明确的二维键 (channelNo, propertyId)，前端可以按通道分组渲染，3D 可以按通道定位。
 */
public class DeviceChannel {

    public Long id;

    public Long deviceId;

    public String channelNo;

    /** CONNECTOR / STRING / PACK / LENS / PHASE。 */
    public String channelType;

    public String name;

    public boolean enabled = true;

    public String metadata;

    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
