package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(name = "CommandRequest", description = "通道命令结果回传")
public record CommandRequest(
        String protocol, String protocolDeviceId, String type, Map<String, Object> params, String operator) {}
