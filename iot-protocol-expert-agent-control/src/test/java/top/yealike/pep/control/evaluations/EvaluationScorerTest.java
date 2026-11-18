package top.yealike.pep.control.evaluations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.yealike.pep.control.agents.model.ModelProfileService;
import top.yealike.pep.control.evaluations.EvaluationRecords.EvalRun;
import top.yealike.pep.control.evaluations.EvaluationRecords.Result;
import top.yealike.pep.control.evaluations.EvaluationRecords.Score;
import top.yealike.pep.control.runs.RunRecords.Run;

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
