package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "LoginRequest", description = "设备登录请求")
public record LoginRequest(
        String protocol,
        String protocolDeviceId,
        String pileCode,
        int pileType,
        int gunCount,
        int protocolVersion,
        String programVersion,
        int networkType,
        String simCard,
        int operator,
        String vendor,
        String model,
        String serialNumber,
        String firmwareVersion,
        int connectorCount,
        String sessionId) {}
