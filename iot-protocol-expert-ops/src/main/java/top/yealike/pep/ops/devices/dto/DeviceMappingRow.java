package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "DeviceMappingRow", description = "Gateway 设备映射行")
public record DeviceMappingRow(
        Long id,
        String deviceSn,
        String protocol,
        String thirdPartyDeviceId,
        boolean enabled,
        List<DeviceMappingConnector> connectors) {}
