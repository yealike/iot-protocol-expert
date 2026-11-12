package top.yealike.ipe.protocol.telemetry;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
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
}
