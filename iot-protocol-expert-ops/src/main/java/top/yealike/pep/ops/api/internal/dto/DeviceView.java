package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(name = "DeviceView", description = "设备视图")
public record DeviceView(
        Long id,
        String sn,
        String protocol,
        int gunCount,
        boolean enabled,
        boolean allowSimulate,
        boolean allowWork,
        int maxPowerPercent,
        boolean online,
        LocalDateTime lastSeen,
        int protocolVersion,
        String programVersion,
        int pileType,
        int networkType,
        String simCard,
        int operator,
        String chargePointVendor,
        String chargePointModel) {}
