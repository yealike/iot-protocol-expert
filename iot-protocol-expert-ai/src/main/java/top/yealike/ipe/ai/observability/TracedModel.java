package top.yealike.ipe.ai.observability;

import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ToolSchema;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.util.List;
import reactor.core.publisher.Flux;

/** Creates a model span without hiding the underlying model implementation. */
public final class TracedModel implements Model {
    private final Model delegate;
    private final ObservationRegistry observations;
    private final Tracer tracer;

    public TracedModel(Model delegate, ObservationRegistry observations, Tracer tracer) {
        this.delegate = delegate;
        this.observations = observations;
        this.tracer = tracer;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        var span = tracer.spanBuilder()
                .name("ai model generate")
                .kind(io.micrometer.tracing.Span.Kind.CLIENT)
                .tag("gen_ai.operation.name", "chat")
                .tag("gen_ai.request.model", delegate.getModelName())
                .start();
        return Flux.defer(() -> {
                    try (var ignored = tracer.withSpan(span)) {
                        return delegate.stream(messages, tools, options);
                    }
                })
                .doOnError(span::error)
                .doFinally(signal -> span.end());
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }
}
