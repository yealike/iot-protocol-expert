package top.yealike.pep.runtime.execution.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.extensions.model.openai.exception.OpenAIException;
import org.junit.jupiter.api.Test;

class ModelQuotaFailureTest {
    @Test
    void mapsWrappedQuotaErrorWithoutExposingProviderMessage() {
        Throwable error = new RuntimeException(new OpenAIException(
                "private transport details",
                429,
                "{\"code\":\"QUOTA_TOTAL_EXHAUSTED\",\"msg\":\"private provider details\",\"data\":null}"));

        ModelQuotaFailure failure = ModelQuotaFailure.from(error).orElseThrow();
        assertThat(failure.code()).isEqualTo("QUOTA_TOTAL_EXHAUSTED");
        assertThat(failure.message()).contains("补充额度").doesNotContain("private");
    }

    @Test
    void keepsReadCompatibilityWithLegacyErrorBodies() {
        Throwable error = new OpenAIException("rate limit", 429, "{\"error\":\"QUOTA_EXHAUSTED\"}");

        assertThat(ModelQuotaFailure.from(error).orElseThrow().code()).isEqualTo("QUOTA_EXHAUSTED");
    }

    @Test
    void readsStandardOpenAiErrorObject() {
        Throwable error = new OpenAIException(
                "rate limit",
                429,
                "{\"error\":{\"message\":\"quota\",\"type\":\"rate_limit_error\",\"code\":\"QUOTA_EXHAUSTED\"}}");

        assertThat(ModelQuotaFailure.from(error).orElseThrow().code()).isEqualTo("QUOTA_EXHAUSTED");
    }

    @Test
    void doesNotMisclassifyProviderRateLimitsOrMalformedResponses() {
        assertThat(ModelQuotaFailure.from(new OpenAIException("rate limit", 429, "{\"error\":\"rate_limit\"}")))
                .isEmpty();
        assertThat(ModelQuotaFailure.from(new OpenAIException("bad gateway", 502, "{\"error\":\"QUOTA_EXHAUSTED\"}")))
                .isEmpty();
        assertThat(ModelQuotaFailure.from(new OpenAIException("rate limit", 429, "invalid JSON")))
                .isEmpty();
        assertThat(ModelQuotaFailure.from(new RuntimeException("QUOTA_EXHAUSTED")))
                .isEmpty();
    }
}
