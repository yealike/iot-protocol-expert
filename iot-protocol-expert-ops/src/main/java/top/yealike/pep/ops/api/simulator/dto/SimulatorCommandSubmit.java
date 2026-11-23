package top.yealike.pep.ops.api.simulator.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "模拟器提交设备命令")
public record SimulatorCommandSubmit(
        @Schema(description = "平台设备 ID") Long deviceId,
        @Schema(description = "命令类型") String type,
        @Schema(description = "命令参数") Map<String, Object> params) {}
