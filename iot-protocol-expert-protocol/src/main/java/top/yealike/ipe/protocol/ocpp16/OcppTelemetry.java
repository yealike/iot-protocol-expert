package top.yealike.ipe.protocol.ocpp16;

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
import org.springframework.stereotype.Component;

/** OCPP 消息级观测，与云快充的帧级观测并列。 */
@Component
public class OcppTelemetry {

    private static final Logger log = LoggerFactory.getLogger(OcppTelemetry.class);

    private final Tracer tracer;
    private final MeterRegistry meters;

    public OcppTelemetry(Tracer tracer, MeterRegistry meters) {
        this.tracer = tracer;
        this.meters = meters;
    }

    public TraceContext capture() {
        Span current = tracer.currentSpan();
        return current == null ? null : current.context();
    }

    public Operation start(
            String side, String operation, String action, String messageType, String uniqueId, TraceContext parent) {
        Span.Kind kind = "receive".equals(operation)
                ? Span.Kind.SERVER
                : "send".equals(operation) ? Span.Kind.CLIENT : Span.Kind.PRODUCER;
        Span span = tracer.spanBuilder()
                .name("ocpp16j " + operation + " " + (action == null ? messageType : action))
                .kind(kind)
                .tag("network.transport", "websocket")
                .tag("ipe.protocol", OcppProtocol.PROTOCOL_CODE)
                .tag("ipe.side", side)
                .tag("ipe.operation", operation)
                .tag("ipe.message.type", messageType == null ? "unknown" : messageType)
                .start();
        if (parent == null) {
            span.tag("ipe.parent", "none");
        } else {
            span.tag("ipe.parent", "remote");
        }
        return new Operation(span, side, operation, action, messageType, uniqueId);
    }

    public final class Operation implements AutoCloseable {

        private final Span span;
        private final Tracer.SpanInScope scope;
        private final String side;
        private final String operation;
        private final String action;
        private final String messageType;
        private final String uniqueId;
        private final long started = System.nanoTime();
        private final AtomicBoolean ended = new AtomicBoolean();
        private boolean asynchronous;
        private volatile String outcome = "success";

        private Operation(
                Span span, String side, String operation, String action, String messageType, String uniqueId) {
            this.span = span;
            this.side = side;
            this.operation = operation;
            this.action = action;
            this.messageType = messageType;
            this.uniqueId = uniqueId == null ? "" : uniqueId;
            this.scope = tracer.withSpan(span);
        }

        public void fail(Throwable error) {
            outcome = error instanceof java.util.concurrent.TimeoutException ? "timeout" : "error";
            span.error(error).tag("error.type", error.getClass().getName());
        }

        public void completeOn(ChannelFuture future) {
            asynchronous = true;
            future.addListener(done -> {
                if (!done.isSuccess()) {
                    fail(done.cause() == null ? new IllegalStateException("write cancelled") : done.cause());
                }
                finish();
            });
        }

        private void finish() {
            if (!ended.compareAndSet(false, true)) {
                return;
            }
            long elapsed = System.nanoTime() - started;
            Tags tags = Tags.of(
                    "side",
                    side,
                    "operation",
                    operation,
                    "action",
                    action == null ? "unknown" : action,
                    "message_type",
                    messageType == null ? "unknown" : messageType,
                    "outcome",
                    outcome);
            try (var ignored = tracer.withSpan(span)) {
                meters.counter("ipe.protocol.messages", tags).increment();
                Timer.builder("ipe.protocol.message.duration")
                        .tags(tags)
                        .description("OCPP message processing / socket write / command round trip")
                        .publishPercentileHistogram()
                        .minimumExpectedValue(Duration.ofMillis(1))
                        .maximumExpectedValue(Duration.ofSeconds(30))
                        .register(meters)
                        .record(elapsed, TimeUnit.NANOSECONDS);
                var event = outcome.equals("success") ? log.atInfo() : log.atWarn();
                event.addKeyValue("event.name", "protocol.message")
                        .addKeyValue("ipe.side", side)
                        .addKeyValue("ipe.operation", operation)
                        .addKeyValue("ipe.message.action", action == null ? "" : action)
                        .addKeyValue("ipe.message.id", uniqueId)
                        .addKeyValue("outcome", outcome)
                        .addKeyValue("duration_ms", elapsed / 1_000_000.0)
                        .log("OCPP message completed");
            } finally {
                span.end();
            }
        }

        @Override
        public void close() {
            scope.close();
            if (!asynchronous) {
                finish();
            }
        }
    }
}
