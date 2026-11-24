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
import top.yealike.pep.runtime.contract.RunLineage;

/** 为每次 Agent 模型调用附加稳定幂等头，同一模型的内部重试复用同一逻辑调用 ID。 */
final class ModelCallHeadersMiddleware implements MiddlewareBase {

    private final UUID runId;
    private final RunLineage lineage;

    ModelCallHeadersMiddleware(UUID runId) {
        this(runId, null);
    }

    ModelCallHeadersMiddleware(UUID runId, RunLineage lineage) {
        this.runId = runId;
        this.lineage = lineage;
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
        var headers = GenerateOptions.builder()
                .additionalHeader(
                        ModelCallHeaders.LOGICAL_CALL_ID, UUID.randomUUID().toString())
                .additionalHeader(ModelCallHeaders.ATTEMPT_NO, "1")
                .additionalHeader(ModelCallHeaders.RUN_ID, runId.toString())
                .additionalHeader(
                        ModelCallHeaders.ROOT_RUN_ID, (lineage == null ? runId : lineage.rootRunId()).toString());
        if (lineage != null) {
            headers.additionalHeader(
                            ModelCallHeaders.PARENT_RUN_ID,
                            lineage.parentRunId().toString())
                    .additionalHeader(
                            ModelCallHeaders.A2A_TASK_ID, lineage.taskId().toString());
        }
        GenerateOptions options = GenerateOptions.mergeOptions(headers.build(), input.options());
        return new ModelCallInput(input.messages(), input.tools(), options, input.model());
    }
}
