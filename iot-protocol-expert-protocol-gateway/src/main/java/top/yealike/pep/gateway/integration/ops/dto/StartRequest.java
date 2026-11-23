package top.yealike.pep.gateway.integration.ops.dto;

/** 启动充电判定请求。 */
public record StartRequest(
        String protocol,
        String protocolDeviceId,
        String gunNo,
        int startType,
        String physicalCard,
        String logicalCard,
        String account) {}
