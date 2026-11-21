package top.yealike.pep.ops.devices.entity;

import java.time.LocalDateTime;

/** 设备与第三方协议设备号的唯一映射。枪口映射保存在 {@link DeviceChannel}。 */
public class DeviceProtocolMapping {

    public Long id;
    public Long deviceId;
    public String protocol;
    public String thirdPartyDeviceId;
    public boolean enabled = true;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
