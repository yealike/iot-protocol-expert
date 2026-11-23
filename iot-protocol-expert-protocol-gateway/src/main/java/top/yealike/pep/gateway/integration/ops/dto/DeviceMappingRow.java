package top.yealike.pep.gateway.integration.ops.dto;

import java.util.List;

/** 设备身份映射分页行。 */
public record DeviceMappingRow(
        Long id,
        String deviceSn,
        String protocol,
        String thirdPartyDeviceId,
        boolean enabled,
        List<DeviceMappingConnector> connectors) {}
