package top.yealike.pep.gateway.integration.ops.dto;

import java.time.LocalDateTime;

/** 运维平台返回的充电桩档案。 */
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
