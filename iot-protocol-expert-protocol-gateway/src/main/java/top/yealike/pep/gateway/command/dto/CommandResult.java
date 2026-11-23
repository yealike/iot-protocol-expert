package top.yealike.pep.gateway.command.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/** 通道命令执行结果。 */
@Schema(name = "ChannelCommandResult", description = "通道命令执行结果")
public record CommandResult(
        @Schema(description = "是否执行成功") boolean success,
        @Schema(description = "结果说明") String message,
        @Schema(description = "命令返回数据") Map<String, Object> data) {}
