package top.yealike.ipe.ai.run;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Run、事件与工具调用。状态迁移必须显式经过仓储方法，避免非法跳转。 */
public final class RunRecords {

    private RunRecords() {}

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String PAUSE_REQUESTED = "PAUSE_REQUESTED";
    public static final String PAUSED = "PAUSED";
    public static final String CANCEL_REQUESTED = "CANCEL_REQUESTED";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    public record Run(
            UUID id,
            UUID conversationId,
            UUID userMessageId,
            String actor,
            java.util.List<String> actorRoles,
            Long actorAuthVersion,
            String question,
            String agentKey,
            String agentVersion,
            String modelKey,
            String status,
            int priority,
            long queueSeq,
            String idempotencyKey,
            String policyVersion,
            String leaseOwner,
            Instant leaseUntil,
            Map<String, Object> budget,
            Map<String, Object> usage,
            String errorCode,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            Instant updatedAt) {

        public boolean terminal() {
            return COMPLETED.equals(status) || FAILED.equals(status) || CANCELLED.equals(status);
        }
    }

    public record RunEvent(
            long cursor,
            UUID conversationId,
            UUID runId,
            String type,
            Map<String, Object> payload,
            Instant createdAt) {}

    public record ToolCall(
            UUID id,
            UUID runId,
            UUID conversationId,
            String toolName,
            String status,
            String decision,
            String denyReason,
            Map<String, Object> argsSummary,
            Map<String, Object> resultSummary,
            Integer durationMs,
            String traceId,
            Instant createdAt,
            Instant finishedAt) {}

    public record RunView(
            UUID id,
            UUID conversationId,
            String question,
            String agentKey,
            String status,
            int priority,
            String errorCode,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            List<ToolCall> toolCalls) {}
}
