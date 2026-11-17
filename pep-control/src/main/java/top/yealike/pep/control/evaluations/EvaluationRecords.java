package top.yealike.pep.control.evaluations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Evaluation datasets, executions, results and scores. */
public final class EvaluationRecords {

    private EvaluationRecords() {}

    public record Dataset(
            UUID id,
            String datasetKey,
            String name,
            String description,
            String status,
            long revision,
            String ownerId,
            Instant createdAt,
            Instant updatedAt) {}

    public record Case(
            UUID id,
            UUID datasetId,
            String caseKey,
            Map<String, Object> input,
            Map<String, Object> expectedOutput,
            Map<String, Object> assertions,
            List<String> tags,
            BigDecimal weight,
            boolean enabled,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    public record EvalRun(
            UUID id,
            String mode,
            UUID datasetId,
            Long datasetRevision,
            String agentKey,
            String agentVersion,
            UUID agentReleaseId,
            String manifestDigest,
            List<Map<String, Object>> evaluatorSnapshot,
            String status,
            String requestedBy,
            List<String> requestedRoles,
            List<String> requestedPermissions,
            long requestedAuthVersion,
            int totalItems,
            int completedItems,
            int failedItems,
            Map<String, Object> config,
            Map<String, Object> summary,
            String errorCode,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            Instant updatedAt) {}

    public record Result(
            UUID id,
            UUID evalRunId,
            UUID caseId,
            Long caseRevision,
            UUID conversationId,
            UUID runId,
            String traceId,
            String rootSpanId,
            String finalStatus,
            Map<String, Object> inputSnapshot,
            Map<String, Object> expectedSnapshot,
            Map<String, Object> outputSnapshot,
            Long durationMs,
            Long inputTokens,
            Long outputTokens,
            BigDecimal estimatedCost,
            String status,
            String errorCode,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt) {}

    public record Score(
            UUID id,
            UUID resultId,
            String evaluatorKey,
            long evaluatorVersion,
            String scope,
            String traceId,
            String spanId,
            String metricKey,
            String scoreType,
            BigDecimal scoreNumber,
            Boolean scoreBoolean,
            String scoreLabel,
            String scoreText,
            String reason,
            Map<String, Object> evidence,
            String source,
            String status,
            String reviewerId,
            Instant createdAt) {}

    public record CreateDataset(String datasetKey, String name, String description) {}

    public record CreateCase(
            String caseKey,
            Map<String, Object> input,
            Map<String, Object> expectedOutput,
            Map<String, Object> assertions,
            List<String> tags,
            BigDecimal weight,
            Boolean enabled) {}

    public record CreateExperiment(
            UUID datasetId,
            String agentKey,
            String agentVersion,
            List<String> evaluatorKeys,
            UUID judgeModelId,
            Integer maxConcurrency,
            Integer maxItems) {}

    public record CreateHumanScore(
            String spanId,
            String metricKey,
            String scoreType,
            BigDecimal scoreNumber,
            Boolean scoreBoolean,
            String scoreLabel,
            String scoreText,
            String reason,
            Map<String, Object> evidence) {}
}
