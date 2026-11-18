package top.yealike.pep.observability.protocol;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.contextpropagation.ObservationAwareSpanThreadLocalAccessor;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

class ContextPropagationTest {
    @Test
    void decoratedWorkerReceivesObservationAndIsCleanForTheNextTask() throws Exception {
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        var accessors = new ContextRegistry().registerThreadLocalAccessor(new ObservationThreadLocalAccessor(registry));
        var decorator = new ContextPropagatingTaskDecorator(
                ContextSnapshotFactory.builder().contextRegistry(accessors).build());
        var observation = Observation.start("async-test", registry);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var received = new CompletableFuture<Observation>();
            try (var scope = observation.openScope()) {
                executor.execute(decorator.decorate(() -> received.complete(registry.getCurrentObservation())));
                assertSame(observation, received.get(2, TimeUnit.SECONDS));
            }
            assertNull(executor.submit(registry::getCurrentObservation).get(2, TimeUnit.SECONDS));
            assertNull(registry.getCurrentObservation());
        } finally {
            observation.stop();
        }
    }

    @Test
    void decoratedWorkerReceivesDirectTracerSpanAndMdc() throws Exception {
        var observationRegistry = ObservationRegistry.create();
        var tracer = new SimpleTracer();
        var accessors = new ContextRegistry()
                .registerThreadLocalAccessor(new ObservationThreadLocalAccessor(observationRegistry))
                .registerThreadLocalAccessor(new ObservationAwareSpanThreadLocalAccessor(observationRegistry, tracer))
                .registerThreadLocalAccessor(new Slf4jThreadLocalAccessor("traceId", "spanId"));
        var decorator = new ContextPropagatingTaskDecorator(
                ContextSnapshotFactory.builder().contextRegistry(accessors).build());
        var span = tracer.nextSpan().name("ocpp-direct").start();
        try (var executor = Executors.newSingleThreadExecutor();
                var ignored = tracer.withSpan(span)) {
            MDC.put("traceId", span.context().traceId());
            MDC.put("spanId", span.context().spanId());
            var receivedTrace = new CompletableFuture<String>();
            var receivedMdc = new CompletableFuture<String>();
            executor.execute(decorator.decorate(() -> {
                receivedTrace.complete(tracer.currentSpan().context().traceId());
                receivedMdc.complete(MDC.get("traceId"));
            }));

            assertEquals(span.context().traceId(), receivedTrace.get(2, TimeUnit.SECONDS));
            assertEquals(span.context().traceId(), receivedMdc.get(2, TimeUnit.SECONDS));
        } finally {
            MDC.clear();
            span.end();
        }
    }
}
