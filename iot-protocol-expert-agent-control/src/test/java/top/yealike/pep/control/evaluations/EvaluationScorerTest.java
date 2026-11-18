package top.yealike.pep.control.evaluations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.yealike.pep.control.agents.model.ModelProfileService;
import top.yealike.pep.control.evaluations.EvaluationRecords.EvalRun;
import top.yealike.pep.control.evaluations.EvaluationRecords.Result;
import top.yealike.pep.control.evaluations.EvaluationRecords.Score;
import top.yealike.pep.control.runs.RunRecords.Run;
import top.yealike.pep.control.runs.RunRecords.ToolCall;

class EvaluationScorerTest {
    @Test
    void preservesNumericJudgeScoresWithObjectStringOrListEvidence() {
        for (String evidence : List.of("{\"fact\":\"supported\"}", "\"supported\"", "[\"supported\"]", "null")) {
            Score score =
                    judge("{\"score\":0.9,\"label\":\"good\",\"reason\":\"supported\",\"evidence\":" + evidence + "}");
            assertThat(score.scoreType()).isEqualTo("NUMERIC");
            assertThat(score.scoreNumber()).isEqualByComparingTo("0.9");
            assertThat(score.source()).isEqualTo("LLM_JUDGE");
            assertThat(score.evidence()).isNotNull();
        }
    }

    @Test
    void invalidJudgeOutputRemainsExplicitFailureInsteadOfInventingScore() {
        for (String response : List.of("not JSON", "{\"score\":1.2}", "{\"score\":\"NaN\"}")) {
            Score score = judge(response);
            assertThat(score.scoreNumber()).isNull();
            assertThat(score.scoreLabel()).isEqualTo("ERROR");
            assertThat(score.scoreText()).isEqualTo("EVALUATOR_FAILED");
        }
    }

    @Test
    void judgeReceivesPersistedToolArgumentsAndResultEvidence() {
        EvaluationRepository repository = mock(EvaluationRepository.class);
        ModelProfileService models = mock(ModelProfileService.class);
        UUID modelId = UUID.randomUUID();
        when(models.completeEvaluation(eq(modelId), anyString(), anyString()))
                .thenReturn(
                        "{\"score\":0.9,\"label\":\"supported\",\"reason\":\"evidence\",\"evidence\":{\"summary\":\"ok\"}}");
        Result result = mock(Result.class);
        when(result.id()).thenReturn(UUID.randomUUID());
        when(result.inputSnapshot()).thenReturn(Map.of());
        Run run = mock(Run.class);
        when(run.status()).thenReturn("COMPLETED");
        when(run.question()).thenReturn("文档数量是多少？");
        EvalRun experiment = mock(EvalRun.class);
        when(experiment.config()).thenReturn(Map.of("judgeModelId", modelId.toString()));
        when(experiment.evaluatorSnapshot()).thenReturn(List.of(Map.of("key", "answer_quality")));
        ToolCall tool = new ToolCall(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "list_documents",
                "SUCCEEDED",
                "ALLOW",
                null,
                Map.of("text", "{\"path\":\"reference\"}"),
                Map.of("text", "{\"documents\":0,\"marker\":\"FLOW_OK\"}"),
                20,
                null,
                null,
                null);

        new EvaluationScorer(repository, models).score(result, run, experiment, "0 条，FLOW_OK", List.of(tool));

        verify(models)
                .completeEvaluation(
                        eq(modelId),
                        anyString(),
                        argThat(prompt -> prompt.contains("list_documents")
                                && prompt.contains("\\\"documents\\\":0")
                                && prompt.contains("FLOW_OK")
                                && prompt.contains("reference")));
    }

    @Test
    void judgeToolEvidenceHasABoundedOverallContext() {
        EvaluationRepository repository = mock(EvaluationRepository.class);
        ModelProfileService models = mock(ModelProfileService.class);
        UUID modelId = UUID.randomUUID();
        when(models.completeEvaluation(eq(modelId), anyString(), anyString()))
                .thenReturn("{\"score\":0.9,\"label\":\"supported\",\"reason\":\"evidence\",\"evidence\":{}}");
        Result result = mock(Result.class);
        when(result.id()).thenReturn(UUID.randomUUID());
        when(result.inputSnapshot()).thenReturn(Map.of());
        Run run = mock(Run.class);
        when(run.status()).thenReturn("COMPLETED");
        when(run.question()).thenReturn("bounded context");
        EvalRun experiment = mock(EvalRun.class);
        when(experiment.config()).thenReturn(Map.of("judgeModelId", modelId.toString()));
        when(experiment.evaluatorSnapshot()).thenReturn(List.of(Map.of("key", "answer_quality")));
        List<ToolCall> tools = java.util.stream.IntStream.range(0, 32)
                .mapToObj(index -> new ToolCall(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "tool-" + index,
                        "SUCCEEDED",
                        "ALLOW",
                        null,
                        Map.of("text", "arguments-" + "a".repeat(8_000)),
                        Map.of("text", "result-" + "r".repeat(8_000)),
                        10,
                        null,
                        null,
                        null))
                .toList();

        new EvaluationScorer(repository, models).score(result, run, experiment, "answer", tools);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(models).completeEvaluation(eq(modelId), anyString(), prompt.capture());
        assertThat(prompt.getValue()).hasSizeLessThan(30_000).contains("\"truncated\":true");
    }

    @Test
    void treatsPendingToolAuthorizationAsUnknownInsteadOfDenied() {
        EvaluationRepository repository = mock(EvaluationRepository.class);
        ModelProfileService models = mock(ModelProfileService.class);
        Result result = mock(Result.class);
        when(result.id()).thenReturn(UUID.randomUUID());
        when(result.inputSnapshot()).thenReturn(Map.of());
        Run run = mock(Run.class);
        when(run.status()).thenReturn("COMPLETED");
        EvalRun experiment = mock(EvalRun.class);
        when(experiment.config()).thenReturn(Map.of());
        when(experiment.evaluatorSnapshot())
                .thenReturn(List.of(Map.of("key", "task_completion"), Map.of("key", "tool_authorization_compliant")));
        ToolCall pending = new ToolCall(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "pending-tool",
                "FAILED",
                "PENDING",
                null,
                Map.of(),
                Map.of(),
                10,
                null,
                null,
                null);

        List<Score> scores =
                new EvaluationScorer(repository, models).score(result, run, experiment, "answer", List.of(pending));

        Score authorization = scores.stream()
                .filter(score -> "tool_authorization_compliant".equals(score.metricKey()))
                .findFirst()
                .orElseThrow();
        assertThat(authorization.scoreType()).isEqualTo("CATEGORICAL");
        assertThat(authorization.scoreText()).isEqualTo("UNKNOWN");
        assertThat(authorization.scoreBoolean()).isNull();
        assertThat(authorization.evidence()).containsEntry("deniedCalls", 0L).containsEntry("unknownCalls", 1L);
    }

    @Test
    void onlyExplicitDeniedCallsMakeAuthorizationNonCompliant() {
        EvaluationRepository repository = mock(EvaluationRepository.class);
        ModelProfileService models = mock(ModelProfileService.class);
        Result result = mock(Result.class);
        when(result.id()).thenReturn(UUID.randomUUID());
        when(result.inputSnapshot()).thenReturn(Map.of());
        Run run = mock(Run.class);
        when(run.status()).thenReturn("COMPLETED");
        EvalRun experiment = mock(EvalRun.class);
        when(experiment.config()).thenReturn(Map.of());
        when(experiment.evaluatorSnapshot())
                .thenReturn(List.of(Map.of("key", "task_completion"), Map.of("key", "tool_authorization_compliant")));
        ToolCall denied = new ToolCall(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "denied-tool",
                "FAILED",
                "DENIED",
                "not permitted",
                Map.of(),
                Map.of(),
                10,
                null,
                null,
                null);

        List<Score> scores =
                new EvaluationScorer(repository, models).score(result, run, experiment, "answer", List.of(denied));

        Score authorization = scores.stream()
                .filter(score -> "tool_authorization_compliant".equals(score.metricKey()))
                .findFirst()
                .orElseThrow();
        assertThat(authorization.scoreType()).isEqualTo("BOOLEAN");
        assertThat(authorization.scoreBoolean()).isFalse();
        assertThat(authorization.evidence()).containsEntry("deniedCalls", 1L).containsEntry("unknownCalls", 0L);
    }

    private Score judge(String response) {
        EvaluationRepository repository = mock(EvaluationRepository.class);
        ModelProfileService models = mock(ModelProfileService.class);
        UUID modelId = UUID.randomUUID();
        when(models.completeEvaluation(eq(modelId), anyString(), anyString())).thenReturn(response);
        Result result = mock(Result.class);
        when(result.id()).thenReturn(UUID.randomUUID());
        when(result.inputSnapshot()).thenReturn(Map.of());
        Run run = mock(Run.class);
        when(run.status()).thenReturn("COMPLETED");
        when(run.question()).thenReturn("synthetic question");
        EvalRun experiment = mock(EvalRun.class);
        when(experiment.config()).thenReturn(Map.of("judgeModelId", modelId.toString()));
        when(experiment.evaluatorSnapshot()).thenReturn(List.of(Map.of("key", "answer_quality")));

        List<Score> scores =
                new EvaluationScorer(repository, models).score(result, run, experiment, "answer", List.of());

        verify(repository).insertScore(any());
        return scores.getFirst();
    }
}
