package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ProtocolMappingInput", description = "充电枪第三方协议映射输入")
public record ProtocolMappingInput(
        @Schema(description = "平台枪号，输入 1 至 99，保存时补齐为两位") String gunNo,

        @Schema(description = "第三方设备号；云快充桩号或 OCPP chargePointId")
        String thirdPartyDeviceId,

        @Schema(description = "第三方枪号，范围 1 至 99；留空时使用平台枪号") String protocolConnectorNo,
        @Schema(description = "是否启用；同一台充电桩的枪口配置必须使用同一值") Boolean enabled) {}
