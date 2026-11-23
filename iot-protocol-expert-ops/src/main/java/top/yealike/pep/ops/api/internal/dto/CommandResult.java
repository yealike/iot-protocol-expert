package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(name = "CommandResult", description = "通道命令回传结果")
public record CommandResult(boolean success, String message, Map<String, Object> data) {}
