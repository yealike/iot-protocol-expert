package top.yealike.pep.runtime.execution;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import java.util.UUID;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import top.yealike.pep.model.contract.ModelCallHeaders;

/** 为每次 Agent 模型调用附加稳定幂等头，同一模型的内部重试复用同一逻辑调用 ID。 */
final class ModelCallHeadersMiddleware implements MiddlewareBase {

    private final UUID runId;

    ModelCallHeadersMiddleware(UUID runId) {
        this.runId = runId;
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext context,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(withHeaders(input));
    }

    ModelCallInput withHeaders(ModelCallInput input) {
        GenerateOptions headers = GenerateOptions.builder()
                .additionalHeader(
                        ModelCallHeaders.LOGICAL_CALL_ID, UUID.randomUUID().toString())
                .additionalHeader(ModelCallHeaders.ATTEMPT_NO, "1")
                .additionalHeader(ModelCallHeaders.RUN_ID, runId.toString())
                .build();
        GenerateOptions options = GenerateOptions.mergeOptions(headers, input.options());
        return new ModelCallInput(input.messages(), input.tools(), options, input.model());
    }
}
