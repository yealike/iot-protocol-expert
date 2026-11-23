package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "DeviceSave", description = "充电桩创建或更新请求")
public record DeviceSave(
        @Schema(description = "8 位设备 SN；创建时留空自动分配，创建后不可修改") String sn,
        @Schema(description = "设备名称") String name,
        @Schema(description = "充电桩产品 ID") Long productId,
        @Schema(description = "是否启用") Boolean enabled,
        @Schema(description = "按枪口配置的第三方设备映射") List<ProtocolMappingInput> protocolMappings) {}
