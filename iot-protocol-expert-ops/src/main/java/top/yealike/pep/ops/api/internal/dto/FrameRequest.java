package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(name = "FrameRequest", description = "协议报文上报")
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
