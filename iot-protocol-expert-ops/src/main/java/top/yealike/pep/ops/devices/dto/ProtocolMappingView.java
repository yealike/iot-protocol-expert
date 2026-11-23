package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ProtocolMappingView", description = "充电枪第三方协议映射")
public record ProtocolMappingView(
        String gunNo, String platformGunNo, String thirdPartyDeviceId, String protocolConnectorNo, boolean enabled) {}
