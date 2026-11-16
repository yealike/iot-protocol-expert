package top.yealike.ipe.ai.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.ScopedSpan;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.SpanCustomizer;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AiObservabilityTest {

    @Test
    void parsesStandardTwoCharacterFlagsAndRejectsInvalidTraceparent() {
        RecordingTracer tracer = new RecordingTracer();
        AiObservability observability = new AiObservability(ObservationRegistry.NOOP, tracer);

        Runnable restored =
                observability.restore("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "test", () -> {});
        restored.run();

        assertThat(tracer.contexts).hasSize(1);
        assertThat(tracer.contexts.getFirst().traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(tracer.contexts.getFirst().spanId()).isEqualTo("00f067aa0ba902b7");
        assertThat(tracer.contexts.getFirst().sampled()).isTrue();

        assertThat(observability.restore("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-1", "test", () -> {}))
                .isNull();
        assertThat(observability.restore("00-00000000000000000000000000000000-00f067aa0ba902b7-01", "test", () -> {}))
                .isNull();
        assertThat(observability.restore("00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01", "test", () -> {}))
                .isNull();
    }

    private static final class RecordingTracer implements Tracer {

        private final List<TraceContext> contexts = new java.util.ArrayList<>();

        @Override
        public Span nextSpan() {
            return Span.NOOP;
        }

        @Override
        public Span nextSpan(Span parent) {
            return Span.NOOP;
        }

        @Override
        public SpanInScope withSpan(Span span) {
            return () -> {};
        }

        @Override
        public ScopedSpan startScopedSpan(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Span.Builder spanBuilder() {
            return new Span.Builder() {
                @Override
                public Span.Builder setParent(TraceContext context) {
                    contexts.add(context);
                    return this;
                }

                @Override
                public Span.Builder setNoParent() {
                    return this;
                }

                @Override
                public Span.Builder name(String name) {
                    return this;
                }

                @Override
                public Span.Builder event(String event) {
                    return this;
                }

                @Override
                public Span.Builder tag(String key, String value) {
                    return this;
                }

                @Override
                public Span.Builder error(Throwable throwable) {
                    return this;
                }

                @Override
                public Span.Builder kind(Span.Kind kind) {
                    return this;
                }

                @Override
                public Span.Builder remoteServiceName(String serviceName) {
                    return this;
                }

                @Override
                public Span.Builder remoteIpAndPort(String ip, int port) {
                    return this;
                }

                @Override
                public Span.Builder startTimestamp(long time, TimeUnit unit) {
                    return this;
                }

                @Override
                public Span start() {
                    return Span.NOOP;
                }
            };
        }

        @Override
        public TraceContext.Builder traceContextBuilder() {
            return new TraceContext.Builder() {
                private String traceId;
                private String spanId;
                private Boolean sampled;

                @Override
                public TraceContext.Builder traceId(String traceId) {
                    this.traceId = traceId;
                    return this;
                }

                @Override
                public TraceContext.Builder parentId(String parentId) {
                    return this;
                }

                @Override
                public TraceContext.Builder spanId(String spanId) {
                    this.spanId = spanId;
                    return this;
                }

                @Override
                public TraceContext.Builder sampled(Boolean sampled) {
                    this.sampled = sampled;
                    return this;
                }

                @Override
                public TraceContext build() {
                    return new TestTraceContext(traceId, spanId, sampled);
                }
            };
        }

        @Override
        public CurrentTraceContext currentTraceContext() {
            return CurrentTraceContext.NOOP;
        }

        @Override
        public SpanCustomizer currentSpanCustomizer() {
            return SpanCustomizer.NOOP;
        }

        @Override
        public Span currentSpan() {
            return Span.NOOP;
        }

        @Override
        public java.util.Map<String, String> getAllBaggage() {
            return java.util.Map.of();
        }

        @Override
        public io.micrometer.tracing.Baggage getBaggage(String name) {
            return io.micrometer.tracing.Baggage.NOOP;
        }

        @Override
        public io.micrometer.tracing.Baggage getBaggage(TraceContext traceContext, String name) {
            return io.micrometer.tracing.Baggage.NOOP;
        }

        @Override
        public io.micrometer.tracing.Baggage createBaggage(String name) {
            return io.micrometer.tracing.Baggage.NOOP;
        }

        @Override
        public io.micrometer.tracing.Baggage createBaggage(String name, String value) {
            return io.micrometer.tracing.Baggage.NOOP;
        }
    }

    private record TestTraceContext(String traceId, String spanId, Boolean sampled) implements TraceContext {

        @Override
        public String parentId() {
            return null;
        }
    }
}
