package top.yealike.pep.simulator.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 模拟时钟配置。 */
@Schema(name = "ClockRequest", description = "模拟时钟配置；三项均可选")
public record ClockRequest(
        @Schema(description = "模拟时钟时间", example = "2026-11-23 10:00:00")
        LocalDateTime time,

        @Schema(description = "模拟时间流速倍数", example = "1.0") Double speed,
        @Schema(description = "向前推进秒数", example = "60") Integer advanceSeconds) {}
