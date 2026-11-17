package top.yealike.pep.control.devices.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 属性时序。
 *
 * <p>当前直接落 PostgreSQL。规模上来后把写入与查询收敛到 {@code StorageStrategy} 接口，换 TDengine / TimescaleDB 时不改业务代码。
 */
public class DevicePropertyTs {

    public Long id;

    public Long deviceId;

    public String channelNo = "";

    public String propertyId;

    public BigDecimal valueNum;

    public String valueText;

    public String valueJson;

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
