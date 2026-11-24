package top.yealike.pep.control.runs.delegation;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import top.yealike.pep.runtime.contract.RunUsage;

/** 当前用户有权查看的子任务摘要。 */
@Schema(description = "会话的委派任务摘要")
public record DelegationView(
        UUID taskId,
        UUID parentRunId,
        UUID childRunId,
        UUID childConversationId,
        String alias,
        String targetAgentKey,
        String targetAgentName,
        A2aTaskState state,
        int depth,
        String taskSummary,
        String resultSummary,
        boolean truncated,
        String errorCode,
        String errorMessage,
        RunUsage usage,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs) {}
