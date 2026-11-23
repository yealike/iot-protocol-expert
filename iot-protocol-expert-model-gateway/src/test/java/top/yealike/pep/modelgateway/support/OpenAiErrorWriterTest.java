package top.yealike.pep.modelgateway.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAiErrorWriterTest {

    @Test
    void buildsStandardOpenAiErrorEnvelope() {
        Map<String, Object> body = OpenAiErrorWriter.body(429, "QUOTA_EXHAUSTED", "额度不足");

        assertThat(body).containsOnlyKeys("error");
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        assertThat(error)
                .containsEntry("message", "额度不足")
                .containsEntry("type", "rate_limit_error")
                .containsEntry("code", "QUOTA_EXHAUSTED")
                .containsKey("param");
    }
}
