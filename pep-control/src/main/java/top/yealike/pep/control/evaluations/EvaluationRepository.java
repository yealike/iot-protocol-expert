package top.yealike.pep.control.evaluations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import top.yealike.pep.control.evaluations.EvaluationRecords.Case;
import top.yealike.pep.control.evaluations.EvaluationRecords.Dataset;
import top.yealike.pep.control.evaluations.EvaluationRecords.EvalRun;
import top.yealike.pep.control.evaluations.EvaluationRecords.Result;
import top.yealike.pep.control.evaluations.EvaluationRecords.Score;
import top.yealike.pep.control.evaluations.mapper.EvaluationMapper;
import top.yealike.pep.control.support.JsonSupport;

@Repository
public class EvaluationRepository {

    private final EvaluationMapper mapper;

    public EvaluationRepository(EvaluationMapper mapper) {
        this.mapper = mapper;
    }

    public int insertDataset(UUID id, String key, String name, String description, String owner) {
        return mapper.insertDataset(id, key, name, description, owner, Instant.now());
    }

    public List<Dataset> datasets(String owner, int limit, int offset) {
        return mapper.listDatasets(owner, limit, offset);
    }

    public Dataset dataset(UUID id) {
        return mapper.findDataset(id).stream().findFirst().orElse(null);
    }

    public boolean updateDataset(UUID id, String owner, long revision, String name, String description, String status) {
        return mapper.updateDataset(id, owner, revision, name, description, status) > 0;
    }

    public boolean archiveDataset(UUID id, String owner) {
        return mapper.archiveDataset(id, owner) > 0;
    }

    public int insertCase(
            UUID id,
            UUID datasetId,
            String key,
            Map<String, Object> input,
            Map<String, Object> expected,
            Map<String, Object> assertions,
            List<String> tags,
            BigDecimal weight,
            boolean enabled) {
        return mapper.insertCase(
                id,
                datasetId,
                key,
                JsonSupport.write(input),
                expected == null ? null : JsonSupport.write(expected),
                JsonSupport.write(assertions),
                JsonSupport.write(tags),
                weight,
                enabled,
                Instant.now());
    }

    public List<Case> cases(UUID datasetId, int limit, int offset) {
        return mapper.listCases(datasetId, limit, offset);
    }

    public Case evaluationCase(UUID id) {
        return mapper.findCase(id).stream().findFirst().orElse(null);
    }

    public boolean updateCase(Case value, long expectedRevision) {
        return mapper.updateCase(
                        value.id(),
                        value.datasetId(),
                        expectedRevision,
                        JsonSupport.write(value.input()),
                        value.expectedOutput() == null ? null : JsonSupport.write(value.expectedOutput()),
                        JsonSupport.write(value.assertions()),
                        JsonSupport.write(value.tags()),
                        value.weight(),
                        value.enabled())
                > 0;
    }

    public int insertEvalRun(EvalRun run) {
        return mapper.insertEvalRun(
                run.id(),
                run.mode(),
                run.datasetId(),
                run.datasetRevision(),
                run.agentKey(),
                run.agentVersion(),
                run.agentReleaseId(),
                run.manifestDigest(),
                JsonSupport.write(run.evaluatorSnapshot()),
                run.requestedBy(),
                JsonSupport.write(run.requestedRoles()),
                JsonSupport.write(run.requestedPermissions()),
                run.requestedAuthVersion(),
                run.totalItems(),
                JsonSupport.write(run.config()),
                Instant.now());
    }

    public List<EvalRun> evalRuns(String owner, int limit, int offset) {
        return mapper.listEvalRuns(owner, limit, offset);
    }

    public EvalRun evalRun(UUID id) {
        return mapper.findEvalRun(id).stream().findFirst().orElse(null);
    }

    public List<UUID> queuedEvalRuns(int limit) {
        return mapper.selectQueuedEvalRuns(limit);
    }

    public boolean claimEvalRun(UUID id, String owner, int leaseSeconds) {
        return mapper.claimEvalRun(id, owner, leaseSeconds) > 0;
    }

    public boolean heartbeatEvalRun(UUID id, String owner, int leaseSeconds) {
        return mapper.heartbeatEvalRun(id, owner, leaseSeconds) > 0;
    }

    public boolean finishEvalRun(
            UUID id,
            String owner,
            String status,
            int completed,
            int failed,
            Map<String, Object> summary,
            String errorCode,
            String errorMessage) {
        return mapper.finishEvalRun(
                        id, owner, status, completed, failed, JsonSupport.write(summary), errorCode, errorMessage)
                > 0;
    }

    public int insertResult(
            UUID id,
            UUID evalRunId,
            UUID caseId,
            Long caseRevision,
            UUID conversationId,
            UUID runId,
            Map<String, Object> input,
            Map<String, Object> expected) {
        return mapper.insertResult(
                id,
                evalRunId,
                caseId,
                caseRevision,
                conversationId,
                runId,
                JsonSupport.write(input),
                expected == null ? null : JsonSupport.write(expected),
                Instant.now());
    }

    public List<Result> results(UUID evalRunId, int limit, int offset) {
        return mapper.listResults(evalRunId, limit, offset);
    }

    public Result result(UUID id) {
        return mapper.findResult(id).stream().findFirst().orElse(null);
    }

    public boolean updateResult(Result result) {
        return mapper.updateResultExecution(
                        result.id(),
                        result.runId(),
                        result.conversationId(),
                        result.traceId(),
                        result.rootSpanId(),
                        result.finalStatus(),
                        JsonSupport.write(result.outputSnapshot()),
                        result.durationMs(),
                        result.inputTokens(),
                        result.outputTokens(),
                        result.status(),
                        result.errorCode(),
                        result.errorMessage())
                > 0;
    }

    public void lockDataset(UUID id) {
        mapper.lockDataset(id);
    }

    public long countPending(String owner) {
        return mapper.countPending(owner);
    }

    public void cancel(UUID id) {
        mapper.cancel(id);
    }

    public List<Map<String, Object>> metricSummary(UUID id) {
        return mapper.metricSummary(id);
    }

    public void release(UUID id, String owner) {
        mapper.release(id, owner);
    }

    public void supersedeHumanScore(UUID resultId, String metricKey, String spanId) {
        mapper.lockResult(resultId);
        mapper.supersedeScore(resultId, metricKey, spanId);
    }

    public boolean insertScore(Score score) {
        return mapper.insertScore(
                        score.id(),
                        score.resultId(),
                        score.evaluatorKey(),
                        score.evaluatorVersion(),
                        score.scope(),
                        score.traceId(),
                        score.spanId(),
                        score.metricKey(),
                        score.scoreType(),
                        score.scoreNumber(),
                        score.scoreBoolean(),
                        score.scoreLabel(),
                        score.scoreText(),
                        score.reason(),
                        score.evidence() == null ? null : JsonSupport.write(score.evidence()),
                        score.source(),
                        score.reviewerId(),
                        Instant.now())
                > 0;
    }

    public List<Score> scores(UUID resultId) {
        return mapper.listScores(resultId);
    }

    public List<UUID> unscoredRuns(int limit) {
        return mapper.selectUnscoredRuns(limit);
    }

    public Map<String, Object> runData(UUID runId) {
        return mapper.runData(runId);
    }

    public List<Map<String, Object>> runTools(UUID runId) {
        return mapper.runTools(runId);
    }

    public List<Map<String, Object>> runMessages(UUID runId) {
        return mapper.runMessages(runId);
    }
}
