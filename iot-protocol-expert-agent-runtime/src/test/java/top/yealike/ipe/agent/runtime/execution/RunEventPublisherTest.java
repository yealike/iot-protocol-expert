package top.yealike.ipe.agent.runtime.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import top.yealike.ipe.agent.runtime.control.ControlClient;
import top.yealike.ipe.ai.contract.RunEvent;
import top.yealike.ipe.ai.contract.RunEventAck;
import top.yealike.ipe.ai.contract.RunEventBatch;

class RunEventPublisherTest {

    @Test
    void sourceSeqStartsAtOneAndDuplicateAckClearsWholeBatch() {
        ControlClient control = mock(ControlClient.class);
        AtomicInteger calls = new AtomicInteger();
        List<RunEventBatch> batches = new ArrayList<>();
        when(control.events(any())).thenAnswer(invocation -> {
            RunEventBatch batch = invocation.getArgument(0);
            batches.add(batch);
            if (calls.getAndIncrement() == 0) {
                return new RunEventAck(
                        batch.events().stream().map(RunEvent::sourceSeq).toList(), 42L);
            }
            // Control 对已经落库批次的去重重试返回空 acceptedSourceSeq，必须视为已确认。
            return new RunEventAck(List.of(), 0L);
        });

        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        RunEventPublisher publisher = new RunEventPublisher(
                control, new SimpleMeterRegistry(), scheduler, UUID.randomUUID(), 7L, "runtime-test");
        scheduler.shutdownNow();
        try {
            publisher.emit("run.started", java.util.Map.of("runId", "r"));
            publisher.emit("assistant.delta", java.util.Map.of("delta", "a"));
            publisher.emit("assistant.delta", java.util.Map.of("delta", "b"));

            assertThat(publisher.flushAndAwait(Duration.ofSeconds(2))).isTrue();
            assertThat(publisher.pendingCount()).isZero();
            assertThat(batches).hasSize(1);
            assertThat(batches.getFirst().events())
                    .extracting(RunEvent::sourceSeq)
                    .containsExactly(1L, 2L, 3L);
        } finally {
            publisher.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void ackOnlyRemovesContiguousAcceptedPrefix() {
        ControlClient control = mock(ControlClient.class);
        AtomicInteger calls = new AtomicInteger();
        when(control.events(any())).thenAnswer(invocation -> {
            RunEventBatch batch = invocation.getArgument(0);
            if (calls.getAndIncrement() == 0) {
                return new RunEventAck(List.of(2L), 9L);
            }
            return new RunEventAck(
                    batch.events().stream().map(RunEvent::sourceSeq).toList(), 10L);
        });

        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        RunEventPublisher publisher = new RunEventPublisher(
                control, new SimpleMeterRegistry(), scheduler, UUID.randomUUID(), 8L, "runtime-test");
        scheduler.shutdownNow();
        try {
            publisher.emit("run.started", java.util.Map.of());
            publisher.emit("assistant.delta", java.util.Map.of("delta", "a"));
            publisher.emit("assistant.delta", java.util.Map.of("delta", "b"));

            assertThat(publisher.flushAndAwait(Duration.ofSeconds(2))).isTrue();
            assertThat(publisher.pendingCount()).isZero();
            assertThat(calls.get()).isGreaterThanOrEqualTo(2);
        } finally {
            publisher.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void batchesAtMostThirtyTwoEvents() {
        ControlClient control = mock(ControlClient.class);
        List<Integer> sizes = new ArrayList<>();
        when(control.events(any())).thenAnswer(invocation -> {
            RunEventBatch batch = invocation.getArgument(0);
            sizes.add(batch.events().size());
            return new RunEventAck(
                    batch.events().stream().map(RunEvent::sourceSeq).toList(), 1L);
        });

        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        RunEventPublisher publisher = new RunEventPublisher(
                control, new SimpleMeterRegistry(), scheduler, UUID.randomUUID(), 9L, "runtime-test");
        scheduler.shutdownNow();
        try {
            for (int index = 0; index < 40; index++) {
                publisher.emit("assistant.delta", java.util.Map.of("delta", "x"));
            }

            assertThat(publisher.flushAndAwait(Duration.ofSeconds(2))).isTrue();
            assertThat(sizes).allMatch(size -> size <= 32);
            assertThat(sizes.stream().mapToInt(Integer::intValue).sum()).isEqualTo(40);
        } finally {
            publisher.close();
            scheduler.shutdownNow();
        }
    }
}
