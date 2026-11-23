package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.Map;

@Schema(name = "TelemetryRequest", description = "通用属性遥测上报")
public record TelemetryRequest(
        String protocol,
        String protocolDeviceId,
        String channelNo,
        Map<String, Object> values,
        LocalDateTime collectedAt) {}
