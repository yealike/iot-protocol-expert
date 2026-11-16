package top.yealike.ipe.ops.device.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 属性最新值：一个属性一行，按值类型选列。
 *
 * <p>唯一键 {@code (device_id, channel_no, property_id)}，无通道属性用空串占位。 查询走 {@code value_num}（数值）或 {@code
 * value_text}（枚举/字符串），复合结构在 {@code value_json} 里，不参与 SQL 过滤。
 */
public class DevicePropertyLatest {

    public Long id;

    public Long deviceId;

    public String channelNo = "";

    public String propertyId;

    public String valueType;

    public BigDecimal valueNum;

    public String valueText;

    public String valueJson;

    public LocalDateTime valueTime;

    public LocalDateTime updatedAt;

    public DevicePropertyLatest() {}

    public DevicePropertyLatest(Long deviceId, String channelNo, String propertyId, String valueType) {
        this.deviceId = deviceId;
        this.channelNo = channelNo == null ? "" : channelNo;
        this.propertyId = propertyId;
        this.valueType = valueType;
    }
}
