package top.yealike.ipe.ai.observability;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.TraceContext;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Propagates observations across the database-backed AI run queue. */
@Component
public class AiObservability {
    private static final Pattern TRACEPARENT = Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([01])$");

    private final ObservationRegistry observations;
    private final Tracer tracer;

    public AiObservability(ObservationRegistry observations, Tracer tracer) {
        this.observations = observations;
        this.tracer = tracer;
    }

    public String traceparent() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null || context.traceId() == null || context.spanId() == null) return null;
        return "00-" + context.traceId() + "-" + context.spanId() + "-"
                + (context.sampled() == null || context.sampled() ? "01" : "00");
    }

    public ContextSnapshot snapshot() {
        return ContextSnapshot.captureAll();
    }

    public Runnable wrap(Runnable task) {
        return snapshot().wrap(task);
    }

    public <T> T observeSupplier(String name, java.util.function.Supplier<T> task) {
        Observation observation = Observation.start(name, observations);
        try (var ignored = observation.openScope()) {
            return task.get();
        } catch (Throwable throwable) {
            observation.error(throwable);
            throw throwable;
        } finally {
            observation.stop();
        }
    }

    public java.util.function.Supplier<Void> observeRunnable(String name, Runnable task) {
        return () -> {
            observeSupplier(name, () -> {
                task.run();
                return null;
            });
            return null;
        };
    }

    public Runnable restore(String traceparent, String name, Runnable task) {
        TraceContext parent = parseTraceparent(traceparent);
        if (parent == null) return null;
        return () -> {
            var span = tracer.spanBuilder()
                    .name(name)
                    .setParent(parent)
                    .tag("ai.trace.restored", "true")
                    .start();
            try (var ignored = tracer.withSpan(span)) {
                task.run();
            } catch (Throwable throwable) {
                span.error(throwable);
                throw throwable;
            } finally {
                span.end();
            }
        };
    }

    public Runnable restoreOrWrap(String traceparent, String name, Runnable task) {
        Runnable restored = restore(traceparent, name, task);
        return restored == null ? wrap(task) : restored;
    }

    private TraceContext parseTraceparent(String value) {
        if (value == null) return null;
        var match = TRACEPARENT.matcher(value);
        if (!match.matches()) return null;
        return tracer.traceContextBuilder()
                .traceId(match.group(1))
                .spanId(match.group(3))
                .sampled("01".equals(match.group(4)))
                .build();
    }
}
