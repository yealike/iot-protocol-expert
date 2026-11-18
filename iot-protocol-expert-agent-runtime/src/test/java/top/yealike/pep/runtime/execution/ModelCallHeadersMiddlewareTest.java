package top.yealike.pep.runtime.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.yealike.pep.model.contract.ModelCallHeaders;

class ModelCallHeadersMiddlewareTest {

    @Test
    void eachModelCallGetsFreshLogicalIdAndKeepsRunIdentity() {
        UUID runId = UUID.randomUUID();
        ModelCallHeadersMiddleware middleware = new ModelCallHeadersMiddleware(runId);
        ModelCallInput input = new ModelCallInput(
                List.of(), List.of(), GenerateOptions.builder().temperature(0.2).build(), null);

        GenerateOptions first = middleware.withHeaders(input).options();
        GenerateOptions second = middleware.withHeaders(input).options();

        assertThat(first.getAdditionalHeaders())
                .containsEntry(ModelCallHeaders.ATTEMPT_NO, "1")
                .containsEntry(ModelCallHeaders.RUN_ID, runId.toString());
        assertThat(first.getTemperature()).isEqualTo(0.2);
        assertThat(UUID.fromString(first.getAdditionalHeaders().get(ModelCallHeaders.LOGICAL_CALL_ID)))
                .isNotEqualTo(UUID.fromString(second.getAdditionalHeaders().get(ModelCallHeaders.LOGICAL_CALL_ID)));
    }
}
