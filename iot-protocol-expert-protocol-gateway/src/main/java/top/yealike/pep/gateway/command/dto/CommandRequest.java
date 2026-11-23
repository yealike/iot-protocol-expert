package top.yealike.pep.gateway.command.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/** 运维平台下发的通道命令。 */
@Schema(name = "ChannelCommandRequest", description = "运维平台下发的通道命令")
public record CommandRequest(
        @Schema(description = "协议类型", example = "YKC16") String protocol,

        @Schema(description = "协议侧设备标识", example = "32010600000001")
        String protocolDeviceId,

        @Schema(description = "命令类型", example = "remote-start")
        String type,

        @Schema(description = "命令参数，随命令类型变化") Map<String, Object> params,
        @Schema(description = "操作人") String operator) {}
