package top.yealike.ipe.ai.evaluation;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import top.yealike.ipe.ai.conversation.ConversationService;
import top.yealike.ipe.ai.evaluation.EvaluationRecords.EvalRun;
import top.yealike.ipe.ai.evaluation.EvaluationRecords.Result;
import top.yealike.ipe.ai.identity.UserIdentity;
import top.yealike.ipe.ai.run.RunRepository;
import top.yealike.ipe.ai.run.RunService;

/** 每轮最多扫描 10 个线上 Run、4 个实验；不创建额外线程池，不阻塞等待 Agent。 */
@Component
public class EvaluationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(EvaluationWorker.class);
    private final EvaluationRepository repository;
    private final EvaluationService evaluations;
    private final ConversationService conversations;
    private final RunService runs;
    private final RunRepository runRepository;
    private final TransactionTemplate transactions;
    private final top.yealike.ipe.ai.observability.AiObservability observability;

    public EvaluationWorker(
            EvaluationRepository repository,
            EvaluationService evaluations,
            ConversationService conversations,
            RunService runs,
            RunRepository runRepository,
            TransactionTemplate transactions,
            top.yealike.ipe.ai.observability.AiObservability observability) {
        this.repository = repository;
        this.evaluations = evaluations;
        this.conversations = conversations;
        this.runs = runs;
        this.runRepository = runRepository;
        this.transactions = transactions;
        this.observability = observability;
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 5000)
    public void tick() {
        for (UUID id : repository.unscoredRuns(10)) {
            try {
                evaluations.scoreCompletedRun(id);
            } catch (RuntimeException exception) {
                LOG.error("Online evaluation failed for run {}", id, exception);
            }
        }
        for (UUID id : repository.queuedEvalRuns(4)) {
            try {
                transactions.executeWithoutResult(status -> advance(id));
            } catch (RuntimeException exception) {
                LOG.error("Dataset evaluation failed for experiment {}", id, exception);
                transactions.executeWithoutResult(status -> {
                    String owner = UUID.randomUUID().toString();
                    if (repository.claimEvalRun(id, owner, 120))
                        evaluations.finish(
                                id, owner, "FAILED", 0, 0, Map.of(), "EVALUATION_FAILED", "评估执行失败，请检查服务端日志后重新发起实验");
                });
            }
        }
    }

    private void advance(UUID id) {
        String owner = UUID.randomUUID().toString();
        if (!repository.claimEvalRun(id, owner, 120)) return;
        EvalRun experiment = repository.evalRun(id);
        UserIdentity identity = new UserIdentity(
                experiment.requestedBy(),
                experiment.requestedBy(),
                experiment.requestedRoles(),
                experiment.requestedPermissions(),
                experiment.requestedAuthVersion());
        boolean cancelling = "CANCEL_REQUESTED".equals(experiment.status());
        boolean active = repository.results(id, 500, 0).stream().anyMatch(r -> "EXECUTING".equals(r.status()));
        for (Result result : repository.results(id, 500, 0)) {
            if ("COMPLETED".equals(result.status()) || "FAILED".equals(result.status())) continue;
            if (cancelling) {
                if (result.runId() != null) runs.cancel(identity, result.runId());
                fail(result, "EXPERIMENT_CANCELLED", "实验已取消");
                continue;
            }
            if (result.runId() == null) {
                if (active) continue;
                String question = String.valueOf(result.inputSnapshot().getOrDefault("question", ""));
                if (question.isBlank()) {
                    fail(result, "CASE_INPUT_INVALID", "用例 input.question 不能为空");
                    continue;
                }
                var conversation = conversations.createEvaluation(
                        identity, experiment.agentKey(), experiment.agentVersion(), "评估 " + experiment.id());
                var run = observability.observeSupplier(
                        "evaluation.case.submit",
                        () -> runs.submit(
                                identity,
                                conversation.id(),
                                question,
                                result.id().toString(),
                                100));
                repository.updateResult(new Result(
                        result.id(),
                        id,
                        result.caseId(),
                        result.caseRevision(),
                        conversation.id(),
                        run.id(),
                        null,
                        null,
                        null,
                        result.inputSnapshot(),
                        result.expectedSnapshot(),
                        Map.of(),
                        null,
                        null,
                        null,
                        null,
                        "EXECUTING",
                        null,
                        null,
                        result.createdAt(),
                        Instant.now()));
                active = true;
            } else {
                var run = runRepository.find(result.runId()).orElseThrow();
                if (!run.terminal()) {
                    if (Duration.between(run.createdAt(), Instant.now()).toMillis() > 600_000) {
                        runs.cancel(identity, run.id());
                        fail(result, "CASE_TIMEOUT", "用例执行超过十分钟，已请求取消");
                    } else {
                        active = true;
                    }
                    continue;
                }
                String answer = evaluations.answer(run.id(), run.conversationId());
                evaluations.finishResult(
                        result,
                        run,
                        run.conversationId(),
                        answer,
                        "COMPLETED".equals(run.status()) ? "COMPLETED" : "FAILED",
                        run.errorCode(),
                        run.errorMessage());
            }
        }
        var results = repository.results(id, 500, 0);
        int completed = (int)
                results.stream().filter(r -> "COMPLETED".equals(r.status())).count();
        int failed =
                (int) results.stream().filter(r -> "FAILED".equals(r.status())).count();
        if (completed + failed == experiment.totalItems()) {
            if (cancelling) evaluations.finish(id, owner, "CANCELLED", completed, failed, Map.of(), null, null);
            else evaluations.updateRunSummary(id, owner, completed, failed);
        } else {
            repository.release(id, owner);
        }
    }

    private void fail(Result result, String code, String message) {
        repository.updateResult(new Result(
                result.id(),
                result.evalRunId(),
                result.caseId(),
                result.caseRevision(),
                result.conversationId(),
                result.runId(),
                result.traceId(),
                result.rootSpanId(),
                result.finalStatus(),
                result.inputSnapshot(),
                result.expectedSnapshot(),
                Map.of(),
                null,
                null,
                null,
                null,
                "FAILED",
                code,
                message,
                result.createdAt(),
                Instant.now()));
    }
}
