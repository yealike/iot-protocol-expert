package top.yealike.pep.simulator.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 选择交互模拟设备请求。 */
@Schema(name = "DeviceSelectRequest", description = "选择交互模拟设备请求")
public record DeviceSelectRequest(
        @Schema(description = "要在交互模拟器中使用的设备 ID", example = "1")
        Long deviceId) {}
