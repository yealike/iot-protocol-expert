package top.yealike.pep.control.api.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record SubmitRun(
        @Schema(description = "本轮用户输入；优先使用此字段", example = "分析这条协议报文")
        String content,

        @Schema(description = "兼容旧客户端的用户问题字段") String question,

        @Schema(description = "客户端生成的幂等键，用于安全重试", example = "6d44b82c-...")
        String idempotencyKey,

        @Schema(description = "任务优先级；当前仅支持 100", example = "100")
        Integer priority) {}
