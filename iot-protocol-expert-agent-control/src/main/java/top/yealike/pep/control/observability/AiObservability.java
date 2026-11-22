package top.yealike.pep.control.observability;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Propagates observations across the database-backed AI run queue. */
@Component
public class AiObservability {
    private static final Pattern TRACEPARENT = Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

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


    private TraceContext parseTraceparent(String value) {
        if (value == null) return null;
        var match = TRACEPARENT.matcher(value);
        if (!match.matches()) return null;
        if (isZero(match.group(1)) || isZero(match.group(2))) return null;
        return tracer.traceContextBuilder()
                .traceId(match.group(1))
                .spanId(match.group(2))
                .sampled((Integer.parseInt(match.group(3), 16) & 1) == 1)
                .build();
    }

    private static boolean isZero(String value) {
        return value.chars().allMatch(character -> character == '0');
    }
}
