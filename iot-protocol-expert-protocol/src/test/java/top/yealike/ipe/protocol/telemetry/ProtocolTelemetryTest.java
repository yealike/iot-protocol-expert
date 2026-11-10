package top.yealike.ipe.protocol.telemetry;

import top.yealike.ipe.protocol.codec.Frame;
import io.netty.channel.embedded.EmbeddedChannel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolTelemetryTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    @org.junit.jupiter.api.AfterEach void closeRegistry() { meters.close(); }
    @Test
    void caughtErrorsAndTimeoutsAreCountedWithoutLeakingThreadContext() {
        var tracer = new SimpleTracer();
        {
            var telemetry = new ProtocolTelemetry(tracer, meters);
            var parent = tracer.spanBuilder().name("HTTP parent").start();
            try (var scope = tracer.withSpan(parent)) {
                try (var ignored = telemetry.start("platform", "receive", new Frame(1, false, 0x03, null), null)) {
                    telemetry.failure(new IllegalArgumentException("invalid input"));
                }
                assertEquals(parent.context(), tracer.currentSpan().context());
                try (var command = telemetry.start("platform", "request", new Frame(2, false, 0x50, null), telemetry.capture())) {
                    command.fail(new TimeoutException());
                }
                assertEquals(parent.context(), tracer.currentSpan().context());
            } finally { parent.end(); }
            assertNull(tracer.currentSpan());
            var received = tracer.getSpans().stream().filter(s -> s.getName().contains("receive")).findFirst().orElseThrow();
            assertTrue(received.getParentId() == null || received.getParentId().isEmpty(), "TCP receives do not invent a remote parent");
            assertNotNull(received.getError());
            var command = tracer.getSpans().stream().filter(s -> s.getName().contains("request")).findFirst().orElseThrow();
            assertEquals(parent.context().spanId(), command.getParentId());
            assertNotNull(command.getError());
            assertEquals(1, meters.get("ipe.protocol.operations").tag("outcome", "error").counter().count());
            assertEquals(1, meters.get("ipe.protocol.operations").tag("outcome", "timeout").counter().count());
            assertTrue(meters.find("ipe.protocol.operations").counters().stream().allMatch(c -> c.getId().getTags().size() == 4));
        }
    }

    @Test
    void asynchronousWriteFailureEndsExactlyOnceAndRestoresScope() {
        var tracer = new SimpleTracer();
        {
            var telemetry = new ProtocolTelemetry(tracer, meters);
            var channel = new EmbeddedChannel();
            var promise = channel.newPromise();
            try (var op = telemetry.start("simulator", "send", new Frame(3, false, 0x03, null), null)) {
                op.completeOn(promise);
            }
            assertNull(tracer.currentSpan());
            assertEquals(java.time.Instant.EPOCH, tracer.lastSpan().getEndTimestamp());
            promise.setFailure(new IllegalStateException("socket closed"));
            channel.runPendingTasks();
            assertTrue(tracer.lastSpan().getEndTimestamp().isAfter(java.time.Instant.EPOCH));
            assertNotNull(tracer.lastSpan().getError());
            assertNull(tracer.currentSpan());
            assertEquals(1, meters.get("ipe.protocol.operations").tag("outcome", "error").counter().count());
            channel.finishAndReleaseAll();
        }
    }
}
