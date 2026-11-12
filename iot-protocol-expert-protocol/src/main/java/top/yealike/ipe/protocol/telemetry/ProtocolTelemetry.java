package top.yealike.ipe.protocol.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.netty.channel.ChannelFuture;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.yealike.ipe.protocol.codec.Frame;
import top.yealike.ipe.protocol.codec.FrameType;

/** Application-scoped instrumentation using Spring-managed Micrometer beans. */
public final class ProtocolTelemetry {
    private static final Logger log = LoggerFactory.getLogger(ProtocolTelemetry.class);
    private final Tracer tracer;
    private final MeterRegistry meters;
    private final ThreadLocal<Operation> active = new ThreadLocal<>();

    public ProtocolTelemetry(Tracer tracer, MeterRegistry meters) {
        this.tracer = tracer;
        this.meters = meters;
    }

    public TraceContext capture() {
        Span current = tracer.currentSpan();
        return current == null ? null : current.context();
    }

    /** A null parent is an independent TCP receive root, never a fabricated remote context. */
    public Operation start(String side, String operation, Frame frame, TraceContext parent) {
        String type =
                FrameType.ofOrNull(frame.frameType()) == null ? "unknown" : String.format("0x%02X", frame.frameType());
        Span.Kind kind = switch (operation) {
            case "receive" -> Span.Kind.SERVER;
            case "request" -> Span.Kind.CLIENT;
            default -> Span.Kind.PRODUCER;
        };
        Span.Builder builder = tracer.spanBuilder()
                .name("yunkuaichong " + operation + " " + type)
                .kind(kind)
                .tag("network.transport", "tcp")
                .tag("ipe.protocol", "yunkuaichong-1.6")
                .tag("ipe.side", side)
                .tag("ipe.operation", operation)
                .tag("ipe.frame.type", type)
                .tag("ipe.frame.sequence", frame.sequence())
                .tag("ipe.frame.encrypted", frame.encrypted());
        if (parent == null) builder.setNoParent();
        else builder.setParent(parent);
        return new Operation(builder.start(), side, operation, type, frame.sequence());
    }

    /** Business handlers sometimes catch an error and return a protocol rejection. */
    public void failure(Throwable error) {
        Operation current = active.get();
        if (current != null) current.fail(error);
    }

    public final class Operation implements AutoCloseable {
        private final Span span;
        private final Tracer.SpanInScope scope;
        private final Operation previous;
        private final String side, operation, type;
        private final long started = System.nanoTime();
        private final AtomicBoolean ended = new AtomicBoolean();
        private int sequence;
        private boolean asynchronous;
        private volatile String outcome = "success";

        private Operation(Span span, String side, String operation, String type, int sequence) {
            this.span = span;
            this.side = side;
            this.operation = operation;
            this.type = type;
            this.sequence = sequence;
            previous = active.get();
            scope = tracer.withSpan(span);
            active.set(this);
        }

        public void fail(Throwable error) {
            outcome = error instanceof java.util.concurrent.TimeoutException
                            || (error
                                            instanceof
                                            top.yealike.ipe.protocol.netty.SessionRegistry.CommandTimeoutException
                                                    timeout
                                    && timeout.isTimeout())
                    ? "timeout"
                    : "error";
            span.error(error).tag("error.type", error.getClass().getName());
        }

        public void frame(Frame frame) {
            sequence = frame.sequence();
            span.tag("ipe.frame.sequence", Integer.toString(sequence))
                    .tag("ipe.frame.encrypted", Boolean.toString(frame.encrypted()));
        }

        /** Close the caller scope immediately; finish on actual socket completion. */
        public void completeOn(ChannelFuture future) {
            asynchronous = true;
            future.addListener(done -> {
                if (!done.isSuccess())
                    fail(done.cause() == null ? new IllegalStateException("write cancelled") : done.cause());
                finish();
            });
        }

        private void finish() {
            if (!ended.compareAndSet(false, true)) return;
            long elapsed = System.nanoTime() - started;
            Tags tags = Tags.of("side", side, "operation", operation, "frame_type", type, "outcome", outcome);
            try (var ignored = tracer.withSpan(span)) {
                meters.counter("ipe.protocol.operations", tags).increment();
                Timer.builder("ipe.protocol.operation.duration")
                        .tags(tags)
                        .description("TCP processing / socket write / complete command round trip")
                        .publishPercentileHistogram()
                        .minimumExpectedValue(Duration.ofMillis(1))
                        .maximumExpectedValue(Duration.ofSeconds(30))
                        .register(meters)
                        .record(elapsed, TimeUnit.NANOSECONDS);
                var event = outcome.equals("success") ? log.atInfo() : log.atWarn();
                event.addKeyValue("event.name", "protocol.operation")
                        .addKeyValue("ipe.side", side)
                        .addKeyValue("ipe.operation", operation)
                        .addKeyValue("ipe.frame.type", type)
                        .addKeyValue("ipe.frame.sequence", sequence)
                        .addKeyValue("outcome", outcome)
                        .addKeyValue("duration_ms", elapsed / 1_000_000.0)
                        .log("Protocol operation completed");
            } finally {
                span.end();
            }
        }

        @Override
        public void close() {
            if (previous == null) active.remove();
            else active.set(previous);
            scope.close();
            if (!asynchronous) finish();
        }
    }
}
