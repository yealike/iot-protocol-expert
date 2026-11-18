package top.yealike.pep.runtime.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.extensions.model.openai.exception.OpenAIException;
import org.junit.jupiter.api.Test;

class ModelQuotaFailureTest {
    @Test
    void mapsWrappedQuotaErrorWithoutExposingProviderMessage() {
        Throwable error = new RuntimeException(new OpenAIException(
                "private transport details",
                429,
                "{\"error\":\"QUOTA_TOTAL_EXHAUSTED\",\"message\":\"private provider details\"}"));

        ModelQuotaFailure failure = ModelQuotaFailure.from(error).orElseThrow();
        assertThat(failure.code()).isEqualTo("QUOTA_TOTAL_EXHAUSTED");
        assertThat(failure.message()).contains("补充额度").doesNotContain("private");
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
