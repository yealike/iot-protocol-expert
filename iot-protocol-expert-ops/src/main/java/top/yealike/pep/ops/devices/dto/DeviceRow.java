package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(name = "DeviceRow", description = "充电桩列表项")
public record DeviceRow(
        Long id,
        String deviceSn,
        String name,
        Long productId,
        String productName,
        String protocol,
        String protocolEnglishName,
        String protocolVersion,
        String state,
        boolean enabled,
        Map<String, Object> keyMetrics,
        String lastSeen,
        int gunCount,
        String matchedGunNo) {}
