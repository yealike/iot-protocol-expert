package top.yealike.pep.control.runs.domain;

import java.time.Instant;
import java.util.UUID;

/** Run 工具调用审计明细。 */
public record ToolCallView(
        UUID id,
        UUID runId,
        UUID conversationId,
        String toolName,
        String status,
        String decision,
        String denyReason,
        ToolCallEvidence argsSummary,
        ToolCallEvidence resultSummary,
        Integer durationMs,
        String traceId,
        Instant createdAt,
        Instant finishedAt) {

    public static ToolCallView from(ToolCall toolCall) {
        return new ToolCallView(
                toolCall.id(),
                toolCall.runId(),
                toolCall.conversationId(),
                toolCall.toolName(),
                toolCall.status(),
                toolCall.decision(),
                toolCall.denyReason(),
                ToolCallEvidence.from(toolCall.argsSummary()),
                ToolCallEvidence.from(toolCall.resultSummary()),
                toolCall.durationMs(),
                toolCall.traceId(),
                toolCall.createdAt(),
                toolCall.finishedAt());
    }
}
