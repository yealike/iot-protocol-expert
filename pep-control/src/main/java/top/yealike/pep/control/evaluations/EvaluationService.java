package top.yealike.pep.control.evaluations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.pep.control.conversations.ConversationRepository;
import top.yealike.pep.control.evaluations.EvaluationRecords.Case;
import top.yealike.pep.control.evaluations.EvaluationRecords.CreateCase;
import top.yealike.pep.control.evaluations.EvaluationRecords.CreateDataset;
import top.yealike.pep.control.evaluations.EvaluationRecords.CreateExperiment;
import top.yealike.pep.control.evaluations.EvaluationRecords.CreateHumanScore;
import top.yealike.pep.control.evaluations.EvaluationRecords.Dataset;
import top.yealike.pep.control.evaluations.EvaluationRecords.EvalRun;
import top.yealike.pep.control.evaluations.EvaluationRecords.Result;
import top.yealike.pep.control.evaluations.EvaluationRecords.Score;
import top.yealike.pep.control.access.UserIdentity;
import top.yealike.pep.control.agents.AgentService;
import top.yealike.pep.control.agents.domain.PlatformRecords.AgentRelease;
import top.yealike.pep.control.agents.model.ModelProfileService;
import top.yealike.pep.control.runs.RunRecords.Run;
import top.yealike.pep.control.runs.RunRecords.ToolCall;
import top.yealike.pep.control.runs.RunRepository;
import top.yealike.pep.control.support.AiExceptions;

@Service
public class EvaluationService {

    private static final Set<String> EVALUATORS = Set.of(
            "task_completion",
            "answer_non_empty",
            "tool_execution_success_ratio",
            "tool_authorization_compliant",
            "input_tokens",
            "output_tokens",
            "duration_ms",
            "answer_quality",
            "expected_answer_exact",
            "assertions_passed");
    private final EvaluationRepository evaluations;
    private final AgentService agents;
    private final RunRepository runs;
    private final ConversationRepository conversations;
    private final EvaluationScorer scorer;
    private final ModelProfileService models;

    public EvaluationService(
            EvaluationRepository evaluations,
            AgentService agents,
            RunRepository runs,
            ConversationRepository conversations,
            EvaluationScorer scorer,
            ModelProfileService models) {
        this.evaluations = evaluations;
        this.agents = agents;
        this.runs = runs;
        this.conversations = conversations;
        this.scorer = scorer;
        this.models = models;
    }

    public List<Dataset> datasets(UserIdentity identity, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = offset(page, limit);
        return evaluations.datasets(identity.actor(), limit, offset);
    }

    @Transactional
    public Dataset createDataset(UserIdentity identity, CreateDataset request) {
        if (request == null
                || request.datasetKey() == null
                || !request.datasetKey().matches("[a-z][a-z0-9_-]{1,99}")) {
            throw AiExceptions.badRequest("数据集 key 必须以小写字母开头，且仅包含小写字母、数字、下划线和短横线");
        }
        String name = requireText(request.name(), "数据集名称不能为空", 200);
        UUID id = UUID.randomUUID();
        evaluations.insertDataset(id, request.datasetKey(), name, request.description(), identity.actor());
        return requireDataset(id, identity.actor());
    }

    @Transactional
    public Dataset updateDataset(UserIdentity identity, UUID id, long revision, String name, String description) {
        requireDataset(id, identity.actor());
        if (!evaluations.updateDataset(
                id, identity.actor(), revision, requireText(name, "数据集名称不能为空", 200), description, "DRAFT")) {
            throw AiExceptions.conflict("REVISION_CONFLICT", "数据集已被修改或已归档，请刷新后重试");
        }
        return requireDataset(id, identity.actor());
    }

    @Transactional
    public void archiveDataset(UserIdentity identity, UUID id) {
        requireDataset(id, identity.actor());
        if (!evaluations.archiveDataset(id, identity.actor())) {
            throw AiExceptions.conflict("DATASET_STATE_CONFLICT", "数据集已归档");
        }
    }

    public List<Case> cases(UserIdentity identity, UUID datasetId, int page, int size) {
        requireDataset(datasetId, identity.actor());
        int limit = Math.min(Math.max(size, 1), 200);
        return evaluations.cases(datasetId, limit, offset(page, limit));
    }

    @Transactional
    public Case createCase(UserIdentity identity, UUID datasetId, CreateCase request) {
        evaluations.lockDataset(datasetId);
        Dataset dataset = requireDataset(datasetId, identity.actor());
        if (!"DRAFT".equals(dataset.status())) {
            throw AiExceptions.conflict("DATASET_NOT_EDITABLE", "仅草稿数据集允许修改用例");
        }
        if (request == null
                || request.caseKey() == null
                || !request.caseKey().matches("[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,119}")) {
            throw AiExceptions.badRequest("Case key 格式非法");
        }
        if (request.input() == null
                || !(request.input().get("question") instanceof String question)
                || question.isBlank()
                || question.length() > 4000) {
            throw AiExceptions.badRequest("Case input.question 必须是 1～4000 字的字符串");
        }
        if (evaluations.cases(datasetId, 501, 0).size() >= 500) {
            throw AiExceptions.conflict("DATASET_CASE_LIMIT", "单个数据集最多包含 500 个用例");
        }
        BigDecimal weight = request.weight() == null ? BigDecimal.ONE : request.weight();
        if (weight.signum() <= 0 || weight.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw AiExceptions.badRequest("Case weight 必须大于 0 且不超过 100");
        }
        UUID id = UUID.randomUUID();
        evaluations.updateDataset(
                datasetId, identity.actor(), dataset.revision(), dataset.name(), dataset.description(), "DRAFT");
        evaluations.insertCase(
                id,
                datasetId,
                request.caseKey(),
                request.input(),
                request.expectedOutput(),
                request.assertions() == null ? Map.of() : request.assertions(),
                request.tags() == null ? List.of() : request.tags(),
                weight,
                request.enabled() == null || request.enabled());
        return evaluations.evaluationCase(id);
    }

    @Transactional
    public EvalRun startExperiment(UserIdentity identity, CreateExperiment request) {
        if (request == null || request.datasetId() == null || request.agentKey() == null) {
            throw AiExceptions.badRequest("必须指定数据集和 Agent");
        }
        if (request.maxConcurrency() != null && request.maxConcurrency() != 1) {
            throw AiExceptions.badRequest("当前实验按顺序执行，maxConcurrency 仅支持 1");
        }
        runs.lockQuota("evaluation:user:" + identity.actor());
        if (evaluations.countPending(identity.actor()) >= 5)
            throw AiExceptions.conflict("EVALUATION_QUEUE_FULL", "每个用户最多允许 5 个未完成实验");
        evaluations.lockDataset(request.datasetId());
        Dataset dataset = requireDataset(request.datasetId(), identity.actor());
        if ("ARCHIVED".equals(dataset.status())) {
            throw AiExceptions.conflict("DATASET_ARCHIVED", "已归档数据集不能启动实验");
        }
        List<Case> cases = evaluations.cases(dataset.id(), 501, 0).stream()
                .filter(Case::enabled)
                .toList();
        int maxItems = request.maxItems() == null ? 200 : Math.min(Math.max(request.maxItems(), 1), 200);
        if (cases.isEmpty()) {
            throw AiExceptions.conflict("DATASET_EMPTY", "数据集没有启用的用例");
        }
        if (cases.size() > maxItems) {
            cases = cases.subList(0, maxItems);
        }
        AgentRelease release = agents.requirePublishedRelease(request.agentKey(), request.agentVersion());
        List<String> keys =
                request.evaluatorKeys() == null || request.evaluatorKeys().isEmpty()
                        ? List.of(
                                "task_completion",
                                "answer_non_empty",
                                "tool_execution_success_ratio",
                                "tool_authorization_compliant")
                        : request.evaluatorKeys();
        for (String key : keys) {
            if (!EVALUATORS.contains(key)) throw AiExceptions.badRequest("未知评估器：" + key);
        }
        if (keys.contains("answer_quality") && request.judgeModelId() == null) {
            throw AiExceptions.badRequest("answer_quality 需要指定 Judge 模型");
        }
        if (request.judgeModelId() != null) {
            models.require(request.judgeModelId());
            if (!keys.contains("answer_quality")) {
                keys = new ArrayList<>(keys);
                keys.add("answer_quality");
            }
        }
        List<Map<String, Object>> snapshot = keys.stream()
                .map(key -> Map.<String, Object>of("key", key, "version", 1))
                .toList();
        Map<String, Object> config = new LinkedHashMap<>();
        if (request.judgeModelId() != null) {
            var judgeModel = models.require(request.judgeModelId());
            config.put("judgeModelId", request.judgeModelId().toString());
            config.put("judgeModelRevision", judgeModel.revision());
            config.put("judgeModelName", judgeModel.modelName());
        }
        config.put("maxConcurrency", 1);
        config.put("caseTimeoutMs", 600_000);

        UUID evalRunId = UUID.randomUUID();
        EvalRun evalRun = new EvalRun(
                evalRunId,
                "DATASET",
                dataset.id(),
                dataset.revision(),
                release.agentKey(),
                release.version(),
                release.id(),
                release.manifestDigest(),
                snapshot,
                "QUEUED",
                identity.actor(),
                identity.roles(),
                identity.permissions(),
                identity.authVersion(),
                cases.size(),
                0,
                0,
                config,
                Map.of(),
                null,
                null,
                null,
                null,
                Instant.now(),
                Instant.now());
        evaluations.insertEvalRun(evalRun);
        for (Case evaluationCase : cases) {
            Map<String, Object> snapshotInput = new LinkedHashMap<>(evaluationCase.input());
            snapshotInput.put("_assertions", evaluationCase.assertions());
            snapshotInput.put("_weight", evaluationCase.weight());
            evaluations.insertResult(
                    UUID.randomUUID(),
                    evalRunId,
                    evaluationCase.id(),
                    evaluationCase.revision(),
                    null,
                    null,
                    snapshotInput,
                    evaluationCase.expectedOutput());
        }
        return evaluations.evalRun(evalRunId);
    }

    @Transactional
    public void cancel(UserIdentity identity, UUID id) {
        requireEvalRun(identity, id);
        evaluations.cancel(id);
    }

    @Transactional
    public Case updateCase(UserIdentity identity, UUID datasetId, UUID id, long revision, CreateCase request) {
        evaluations.lockDataset(datasetId);
        Dataset dataset = requireDataset(datasetId, identity.actor());
        Case existing = evaluations.evaluationCase(id);
        if (existing == null || !existing.datasetId().equals(datasetId)) throw AiExceptions.notFound("用例不存在");
        if (!"DRAFT".equals(dataset.status())) throw AiExceptions.badRequest("仅草稿数据集可编辑");
        if (request.input() == null
                || !(request.input().get("question") instanceof String question)
                || question.isBlank()
                || question.length() > 4000) throw AiExceptions.badRequest("question 长度必须为 1～4000");
        BigDecimal weight = request.weight() == null ? existing.weight() : request.weight();
        if (weight.signum() <= 0 || weight.compareTo(BigDecimal.valueOf(100)) > 0)
            throw AiExceptions.badRequest("weight 必须在 (0,100] 内");
        Case updated = new Case(
                id,
                datasetId,
                existing.caseKey(),
                request.input(),
                request.expectedOutput(),
                request.assertions() == null ? Map.of() : request.assertions(),
                request.tags() == null ? List.of() : request.tags(),
                weight,
                request.enabled() == null || request.enabled(),
                revision,
                existing.createdAt(),
                Instant.now());
        if (!evaluations.updateCase(updated, revision)) throw AiExceptions.conflict("REVISION_CONFLICT", "用例已被修改");
        evaluations.updateDataset(
                datasetId, identity.actor(), dataset.revision(), dataset.name(), dataset.description(), "DRAFT");
        return evaluations.evaluationCase(id);
    }

    public List<EvalRun> evalRuns(UserIdentity identity, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 100);
        return evaluations.evalRuns(identity.actor(), limit, offset(page, limit));
    }

    public EvalRun requireEvalRun(UserIdentity identity, UUID id) {
        EvalRun run = evaluations.evalRun(id);
        if (run == null || !identity.actor().equals(run.requestedBy())) {
            throw AiExceptions.notFound("评估任务不存在");
        }
        return run;
    }

    public List<Result> results(UserIdentity identity, UUID evalRunId, int page, int size) {
        requireEvalRun(identity, evalRunId);
        int limit = Math.min(Math.max(size, 1), 200);
        return evaluations.results(evalRunId, limit, offset(page, limit));
    }

    public Result requireResult(UserIdentity identity, UUID resultId) {
        Result result = evaluations.result(resultId);
        if (result == null) throw AiExceptions.notFound("评估结果不存在");
        requireEvalRun(identity, result.evalRunId());
        return result;
    }

    public List<Score> scores(UserIdentity identity, UUID resultId) {
        requireResult(identity, resultId);
        return evaluations.scores(resultId);
    }

    @Transactional
    public Score addHumanScore(UserIdentity identity, UUID resultId, CreateHumanScore request) {
        Result result = requireResult(identity, resultId);
        if (request == null
                || request.metricKey() == null
                || !request.metricKey().matches("[a-z][a-z0-9_.-]{1,99}")) {
            throw AiExceptions.badRequest("metricKey 格式非法");
        }
        if ("CATEGORICAL".equals(request.scoreType())
                && (request.scoreLabel() == null || request.scoreLabel().isBlank())) {
            throw AiExceptions.badRequest("分类评分标签不能为空");
        }
        if ("TEXT".equals(request.scoreType())
                && (request.scoreText() == null || request.scoreText().isBlank())) {
            throw AiExceptions.badRequest("文本评分不能为空");
        }
        String type = request.scoreType();
        if (type == null) throw AiExceptions.badRequest("评分类型不能为空");
        if (request.spanId() != null && !request.spanId().matches("[0-9a-f]{16}")) {
            throw AiExceptions.badRequest("Span ID 格式非法");
        }
        if (!Set.of("BOOLEAN", "NUMERIC", "CATEGORICAL", "TEXT").contains(type)) {
            throw AiExceptions.badRequest("不支持的人工评分类型");
        }
        if ("NUMERIC".equals(type)
                && (request.scoreNumber() == null
                        || request.scoreNumber().compareTo(BigDecimal.ZERO) < 0
                        || request.scoreNumber().compareTo(BigDecimal.ONE) > 0)) {
            throw AiExceptions.badRequest("NUMERIC 评分必须在 0 到 1 之间");
        }
        if ("BOOLEAN".equals(type) && request.scoreBoolean() == null) {
            throw AiExceptions.badRequest("BOOLEAN 评分不能为空");
        }
        Score score = new Score(
                UUID.randomUUID(),
                resultId,
                "human_review",
                1,
                request.spanId() == null ? "TRACE" : "SPAN",
                result.traceId(),
                request.spanId(),
                request.metricKey(),
                type,
                request.scoreNumber(),
                request.scoreBoolean(),
                request.scoreLabel(),
                request.scoreText(),
                request.reason(),
                request.evidence(),
                "HUMAN",
                "ACTIVE",
                identity.actor(),
                null);
        evaluations.supersedeHumanScore(resultId, request.metricKey(), request.spanId());
        evaluations.insertScore(score);
        return evaluations.scores(resultId).stream()
                .filter(item -> item.id().equals(score.id()))
                .findFirst()
                .orElse(score);
    }

    @Transactional
    public boolean scoreCompletedRun(UUID runId) {
        Run run = runs.find(runId).orElse(null);
        if (run == null || !run.terminal()) return false;
        var conversation = conversations.find(run.conversationId()).orElse(null);
        if (conversation == null || "EVALUATION".equals(conversationSource(run.conversationId()))) return false;
        String traceId = traceId(run.traceContext());
        List<Map<String, Object>> messages = evaluations.runMessages(runId);
        String answer = messages.stream()
                .filter(message -> "assistant".equals(message.get("role")))
                .reduce((first, second) -> second)
                .map(message -> String.valueOf(message.getOrDefault("content", "")))
                .orElse("");
        List<ToolCall> toolCalls = runs.listToolCalls(runId);
        UUID evalRunId = UUID.randomUUID();
        EvalRun evalRun = new EvalRun(
                evalRunId,
                "ONLINE",
                null,
                null,
                run.agentKey(),
                run.agentVersion(),
                run.agentReleaseId(),
                run.manifestDigest(),
                List.of(),
                "RUNNING",
                run.actor(),
                List.of(),
                List.of(),
                0,
                1,
                0,
                0,
                Map.of(),
                Map.of(),
                null,
                null,
                Instant.now(),
                null,
                Instant.now(),
                Instant.now());
        evaluations.insertEvalRun(evalRun);
        evaluations.claimEvalRun(evalRunId, "online", 120);
        UUID resultId = UUID.randomUUID();
        Map<String, Object> input = Map.of("question", run.question() == null ? "" : run.question());
        try {
            evaluations.insertResult(resultId, evalRunId, null, null, run.conversationId(), run.id(), input, null);
        } catch (DuplicateKeyException exception) {
            throw exception;
        }
        Map<String, Object> output = Map.of("answer", answer, "messages", messages);
        Map<String, Object> usage = run.usage() == null ? Map.of() : run.usage();
        long duration = run.startedAt() == null || run.finishedAt() == null
                ? 0
                : Math.max(
                        0,
                        java.time.Duration.between(run.startedAt(), run.finishedAt())
                                .toMillis());
        Result result = new Result(
                resultId,
                evalRunId,
                null,
                null,
                run.conversationId(),
                run.id(),
                traceId,
                null,
                run.status(),
                input,
                null,
                output,
                duration,
                longValue(usage.get("inputTokens")),
                longValue(usage.get("outputTokens")),
                null,
                "COMPLETED",
                null,
                null,
                Instant.now(),
                Instant.now());
        evaluations.updateResult(result);
        EvalRun completedEvalRun = new EvalRun(
                evalRun.id(),
                evalRun.mode(),
                evalRun.datasetId(),
                evalRun.datasetRevision(),
                evalRun.agentKey(),
                evalRun.agentVersion(),
                evalRun.agentReleaseId(),
                evalRun.manifestDigest(),
                evalRun.evaluatorSnapshot(),
                evalRun.status(),
                evalRun.requestedBy(),
                evalRun.requestedRoles(),
                evalRun.requestedPermissions(),
                evalRun.requestedAuthVersion(),
                evalRun.totalItems(),
                1,
                0,
                evalRun.config(),
                evalRun.summary(),
                evalRun.errorCode(),
                evalRun.errorMessage(),
                evalRun.startedAt(),
                evalRun.finishedAt(),
                evalRun.createdAt(),
                evalRun.updatedAt());
        scorer.score(result, run, completedEvalRun, answer, toolCalls);
        evaluations.finishEvalRun(
                evalRunId,
                "online",
                "COMPLETED",
                1,
                0,
                Map.of("runId", runId.toString(), "traceId", traceId == null ? "" : traceId),
                null,
                null);
        return true;
    }

    public EvalRun internalRun(UUID id) {
        return evaluations.evalRun(id);
    }

    public Result internalResult(UUID id) {
        return evaluations.result(id);
    }

    public List<UUID> queuedRuns(int limit) {
        return evaluations.queuedEvalRuns(limit);
    }

    public boolean claim(UUID id, String owner) {
        return evaluations.claimEvalRun(id, owner, 120);
    }

    public boolean heartbeat(UUID id, String owner) {
        return evaluations.heartbeatEvalRun(id, owner, 120);
    }

    public List<Case> internalCases(UUID datasetId) {
        return evaluations.cases(datasetId, 501, 0).stream()
                .filter(Case::enabled)
                .toList();
    }

    public Case internalCase(UUID id) {
        return evaluations.evaluationCase(id);
    }

    public void finish(
            UUID id,
            String owner,
            String status,
            int completed,
            int failed,
            Map<String, Object> summary,
            String errorCode,
            String errorMessage) {
        evaluations.finishEvalRun(id, owner, status, completed, failed, summary, errorCode, errorMessage);
    }

    public boolean updateExecution(Result result) {
        return evaluations.updateResult(result);
    }

    public List<ToolCall> tools(UUID runId) {
        return runs.listToolCalls(runId);
    }

    public String answer(UUID runId, UUID conversationId) {
        return conversations.listMessages(conversationId, 200).stream()
                .filter(message -> "assistant".equals(message.role()))
                .reduce((first, second) -> second)
                .map(message -> message.content() == null ? "" : message.content())
                .orElse("");
    }

    public void scoreExecution(Result result, Run run, EvalRun evalRun, String answer, List<ToolCall> tools) {
        scorer.score(result, run, evalRun, answer, tools);
    }

    public void finishResult(
            Result result,
            Run run,
            UUID conversationId,
            String answer,
            String status,
            String errorCode,
            String errorMessage) {
        String traceId = traceId(run.traceContext());
        Map<String, Object> output = Map.of("answer", answer == null ? "" : answer);
        Map<String, Object> usage = run.usage() == null ? Map.of() : run.usage();
        Long duration = run.startedAt() == null || run.finishedAt() == null
                ? null
                : Math.max(
                        0,
                        java.time.Duration.between(run.startedAt(), run.finishedAt())
                                .toMillis());
        Result updated = new Result(
                result.id(),
                result.evalRunId(),
                result.caseId(),
                result.caseRevision(),
                conversationId,
                run.id(),
                traceId,
                null,
                run.status(),
                result.inputSnapshot(),
                result.expectedSnapshot(),
                output,
                duration,
                longValue(usage.get("inputTokens")),
                longValue(usage.get("outputTokens")),
                null,
                status,
                errorCode,
                errorMessage,
                result.createdAt(),
                Instant.now());
        evaluations.updateResult(updated);
        scoreExecution(updated, run, internalRun(result.evalRunId()), answer, tools(run.id()));
    }

    public void updateRunSummary(UUID evalRunId, String owner, int completed, int failed) {
        Map<String, Object> summary = new LinkedHashMap<>(Map.of(
                "completedItems",
                completed,
                "failedItems",
                failed,
                "executionSuccessRate",
                completed + failed == 0
                        ? 0
                        : BigDecimal.valueOf(completed)
                                .divide(BigDecimal.valueOf(completed + failed), 4, java.math.RoundingMode.HALF_UP)));
        summary.put("metrics", evaluations.metricSummary(evalRunId));
        evaluations.finishEvalRun(evalRunId, owner, "COMPLETED", completed, failed, summary, null, null);
    }

    private static int offset(int page, int size) {
        long offset = (long) Math.max(page, 0) * size;
        if (offset > Integer.MAX_VALUE) throw AiExceptions.badRequest("页码超出范围");
        return (int) offset;
    }

    private Dataset requireDataset(UUID id, String owner) {
        Dataset dataset = evaluations.dataset(id);
        if (dataset == null || !owner.equals(dataset.ownerId())) throw AiExceptions.notFound("评测集不存在");
        return dataset;
    }

    private static String requireText(String value, String message, int maxLength) {
        if (value == null || value.isBlank()) throw AiExceptions.badRequest(message);
        String result = value.strip();
        if (result.length() > maxLength) throw AiExceptions.badRequest("字段长度超过 " + maxLength + " 字");
        return result;
    }

    private String conversationSource(UUID conversationId) {
        return conversations.source(conversationId);
    }

    private static String traceId(String traceparent) {
        if (traceparent == null) return null;
        java.util.regex.Matcher matcher = PatternHolder.TRACEPARENT.matcher(traceparent);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static final class PatternHolder {
        private static final java.util.regex.Pattern TRACEPARENT =
                java.util.regex.Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");
    }
}
