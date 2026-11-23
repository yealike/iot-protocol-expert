package top.yealike.pep.ops.devices.domain;

import java.time.LocalDateTime;

/** 充电枪。枪号两位，从 01 开始；平台枪号为 8 位设备 SN 加 2 位枪号。 */
public class DeviceChannel {

    public Long id;
    public Long deviceId;
    public String channelNo;
    public String platformGunNo;
    public String protocolConnectorNo;
    public String name;
    public boolean enabled = true;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
