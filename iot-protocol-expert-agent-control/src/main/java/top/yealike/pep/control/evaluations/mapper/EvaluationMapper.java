package top.yealike.pep.control.evaluations.mapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.control.evaluations.EvaluationRecords.Case;
import top.yealike.pep.control.evaluations.EvaluationRecords.Dataset;
import top.yealike.pep.control.evaluations.EvaluationRecords.EvalRun;
import top.yealike.pep.control.evaluations.EvaluationRecords.Result;
import top.yealike.pep.control.evaluations.EvaluationRecords.Score;

public interface EvaluationMapper {

    int insertDataset(
            @Param("id") UUID id,
            @Param("datasetKey") String datasetKey,
            @Param("name") String name,
            @Param("description") String description,
            @Param("ownerId") String ownerId,
            @Param("now") Instant now);

    List<Dataset> listDatasets(
            @Param("ownerId") String ownerId, @Param("limit") int limit, @Param("offset") int offset);

    List<Dataset> findDataset(@Param("id") UUID id);

    int updateDataset(
            @Param("id") UUID id,
            @Param("ownerId") String ownerId,
            @Param("expectedRevision") long expectedRevision,
            @Param("name") String name,
            @Param("description") String description,
            @Param("status") String status);

    int insertCase(
            @Param("id") UUID id,
            @Param("datasetId") UUID datasetId,
            @Param("caseKey") String caseKey,
            @Param("inputJson") String inputJson,
            @Param("expectedOutputJson") String expectedOutputJson,
            @Param("assertionsJson") String assertionsJson,
            @Param("tagsJson") String tagsJson,
            @Param("weight") BigDecimal weight,
            @Param("enabled") boolean enabled,
            @Param("now") Instant now);

    List<Case> listCases(@Param("datasetId") UUID datasetId, @Param("limit") int limit, @Param("offset") int offset);

    List<Case> findCase(@Param("id") UUID id);

    int updateCase(
            @Param("id") UUID id,
            @Param("datasetId") UUID datasetId,
            @Param("expectedRevision") long expectedRevision,
            @Param("inputJson") String inputJson,
            @Param("expectedOutputJson") String expectedOutputJson,
            @Param("assertionsJson") String assertionsJson,
            @Param("tagsJson") String tagsJson,
            @Param("weight") BigDecimal weight,
            @Param("enabled") boolean enabled);

    int archiveDataset(@Param("id") UUID id, @Param("ownerId") String ownerId);

    int insertEvalRun(
            @Param("id") UUID id,
            @Param("mode") String mode,
            @Param("datasetId") UUID datasetId,
            @Param("datasetRevision") Long datasetRevision,
            @Param("agentKey") String agentKey,
            @Param("agentVersion") String agentVersion,
            @Param("agentReleaseId") UUID agentReleaseId,
            @Param("manifestDigest") String manifestDigest,
            @Param("evaluatorSnapshotJson") String evaluatorSnapshotJson,
            @Param("requestedBy") String requestedBy,
            @Param("requestedRolesJson") String requestedRolesJson,
            @Param("requestedPermissionsJson") String requestedPermissionsJson,
            @Param("requestedAuthVersion") long requestedAuthVersion,
            @Param("totalItems") int totalItems,
            @Param("configJson") String configJson,
            @Param("now") Instant now);

    List<EvalRun> listEvalRuns(
            @Param("ownerId") String ownerId, @Param("limit") int limit, @Param("offset") int offset);

    List<EvalRun> findEvalRun(@Param("id") UUID id);

    List<UUID> selectQueuedEvalRuns(@Param("limit") int limit);

    int claimEvalRun(@Param("id") UUID id, @Param("owner") String owner, @Param("leaseSeconds") int leaseSeconds);

    int heartbeatEvalRun(@Param("id") UUID id, @Param("owner") String owner, @Param("leaseSeconds") int leaseSeconds);

    int finishEvalRun(
            @Param("id") UUID id,
            @Param("owner") String owner,
            @Param("status") String status,
            @Param("completedItems") int completedItems,
            @Param("failedItems") int failedItems,
            @Param("summaryJson") String summaryJson,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage);

    int insertResult(
            @Param("id") UUID id,
            @Param("evalRunId") UUID evalRunId,
            @Param("caseId") UUID caseId,
            @Param("caseRevision") Long caseRevision,
            @Param("conversationId") UUID conversationId,
            @Param("runId") UUID runId,
            @Param("inputJson") String inputJson,
            @Param("expectedJson") String expectedJson,
            @Param("now") Instant now);

    List<Result> listResults(
            @Param("evalRunId") UUID evalRunId, @Param("limit") int limit, @Param("offset") int offset);

    List<Result> findResult(@Param("id") UUID id);

    int updateResultExecution(
            @Param("id") UUID id,
            @Param("runId") UUID runId,
            @Param("conversationId") UUID conversationId,
            @Param("traceId") String traceId,
            @Param("rootSpanId") String rootSpanId,
            @Param("finalStatus") String finalStatus,
            @Param("outputJson") String outputJson,
            @Param("durationMs") Long durationMs,
            @Param("inputTokens") Long inputTokens,
            @Param("outputTokens") Long outputTokens,
            @Param("status") String status,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage);

    UUID lockDataset(@Param("id") UUID id);

    long countPending(@Param("owner") String owner);

    int cancel(@Param("id") UUID id);

    List<Map<String, Object>> metricSummary(@Param("id") UUID id);

    int release(@Param("id") UUID id, @Param("owner") String owner);

    UUID lockResult(@Param("id") UUID id);

    int supersedeScore(
            @Param("resultId") UUID resultId, @Param("metricKey") String metricKey, @Param("spanId") String spanId);

    int insertScore(
            @Param("id") UUID id,
            @Param("resultId") UUID resultId,
            @Param("evaluatorKey") String evaluatorKey,
            @Param("evaluatorVersion") long evaluatorVersion,
            @Param("scope") String scope,
            @Param("traceId") String traceId,
            @Param("spanId") String spanId,
            @Param("metricKey") String metricKey,
            @Param("scoreType") String scoreType,
            @Param("scoreNumber") BigDecimal scoreNumber,
            @Param("scoreBoolean") Boolean scoreBoolean,
            @Param("scoreLabel") String scoreLabel,
            @Param("scoreText") String scoreText,
            @Param("reason") String reason,
            @Param("evidenceJson") String evidenceJson,
            @Param("source") String source,
            @Param("reviewerId") String reviewerId,
            @Param("now") Instant now);

    List<Score> listScores(@Param("resultId") UUID resultId);

    List<UUID> selectUnscoredRuns(@Param("limit") int limit);

    Map<String, Object> runData(@Param("runId") UUID runId);

    List<Map<String, Object>> runTools(@Param("runId") UUID runId);

    List<Map<String, Object>> runMessages(@Param("runId") UUID runId);
}
