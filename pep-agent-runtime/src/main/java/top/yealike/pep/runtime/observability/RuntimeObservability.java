package top.yealike.pep.runtime.observability;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Runtime 侧观测适配。
 *
 * <p>复用 observability 依赖带来的 Micrometer/OTel Registry；缺少 Tracer 时退化为普通
 * Observation/直接执行，不阻塞主流程。
 */
@Component
public class RuntimeObservability {

    private static final Pattern TRACEPARENT = Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    private final ObjectProvider<ObservationRegistry> observations;
    private final ObjectProvider<Tracer> tracers;

    public RuntimeObservability(ObjectProvider<ObservationRegistry> observations, ObjectProvider<Tracer> tracers) {
        this.observations = observations;
        this.tracers = tracers;
    }

    public <T> T observeSupplier(String name, Supplier<T> task) {
        ObservationRegistry registry = observations.getIfAvailable();
        if (registry == null) {
            return task.get();
        }
        Observation observation = Observation.start(name, registry);
        try (Observation.Scope ignored = observation.openScope()) {
            return task.get();
        } catch (RuntimeException exception) {
            observation.error(exception);
            throw exception;
        } finally {
            observation.stop();
        }
    }

    public Runnable restoreOrWrap(String traceparent, String name, Runnable task) {
        Tracer tracer = tracers.getIfAvailable();
        TraceContext parent = parse(tracer, traceparent);
        if (tracer == null) {
            return task;
        }
        if (parent == null) {
            ContextSnapshot snapshot = ContextSnapshot.captureAll();
            return snapshot.wrap(task);
        }
        return () -> {
            var span = tracer.spanBuilder()
                    .name(name)
                    .setParent(parent)
                    .tag("ai.trace.restored", "true")
                    .start();
            try (var ignored = tracer.withSpan(span)) {
                task.run();
            } catch (RuntimeException exception) {
                span.error(exception);
                throw exception;
            } finally {
                span.end();
            }
        };
    }

    private static TraceContext parse(Tracer tracer, String value) {
        if (tracer == null || value == null) {
            return null;
        }
        var match = TRACEPARENT.matcher(value);
        if (!match.matches()) {
            return null;
        }
        if (isZero(match.group(1)) || isZero(match.group(2))) {
            return null;
        }
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
