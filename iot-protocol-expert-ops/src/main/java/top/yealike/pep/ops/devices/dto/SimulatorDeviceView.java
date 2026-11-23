package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "SimulatorDeviceView", description = "模拟器设备目录项")
public record SimulatorDeviceView(
        @Schema(description = "设备主键") Long id,
        @Schema(description = "8 位平台设备 SN") String sn,
        @Schema(description = "设备名称") String name,
        @Schema(description = "标准化协议编码") String protocol,
        @Schema(description = "第三方协议设备号；未配置时为空") String protocolDeviceId,
        @Schema(description = "产品标准枪数") int gunCount,
        @Schema(description = "充电桩是否在线") boolean online,
        @Schema(description = "设备状态") String state,
        @Schema(description = "设备是否启用") boolean enabled,
        @Schema(description = "设备是否允许模拟") boolean allowSimulate,
        @Schema(description = "已配置的充电枪接入映射") List<SimulatorConnectorView> connectors) {}
