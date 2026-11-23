package top.yealike.pep.gateway.integration.ops.dto;

import java.time.LocalDateTime;

/** 原始报文留存请求。 */
public record FrameRequest(
        String protocol,
        String protocolDeviceId,
        String direction,
        int frameType,
        String frameLabel,
        String messageId,
        int sequenceNo,
        boolean encrypted,
        String bodyHex,
        String wireHex,
        String summary,
        String errorMessage,
        String traceId,
        LocalDateTime createdAt) {}
