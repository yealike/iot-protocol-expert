package top.yealike.pep.gateway.integration.ops.dto;

/** 云快充登录契约。 */
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
