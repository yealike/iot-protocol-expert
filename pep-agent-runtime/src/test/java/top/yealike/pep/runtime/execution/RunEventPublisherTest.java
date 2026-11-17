package top.yealike.pep.runtime.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import top.yealike.pep.runtime.contract.RunEvent;
import top.yealike.pep.runtime.contract.RunEventAck;
import top.yealike.pep.runtime.contract.RunEventBatch;
import top.yealike.pep.runtime.control.ControlClient;

class RunEventPublisherTest {

    @Test
    void duplicateAndInFlightEventsAreAcknowledgedIndependently() throws Exception {
        ControlClient control = mock(ControlClient.class);
        AtomicInteger calls = new AtomicInteger();
        List<RunEventBatch> batches = new ArrayList<>();
        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        CountDownLatch secondEventQueued = new CountDownLatch(1);
        when(control.events(any())).thenAnswer(invocation -> {
            RunEventBatch batch = invocation.getArgument(0);
            batches.add(batch);
            if (calls.getAndIncrement() == 0) {
                firstRequestStarted.countDown();
                secondEventQueued.await(2, TimeUnit.SECONDS);
                return new RunEventAck(
                        batch.events().stream().map(RunEvent::sourceSeq).toList(), 42L);
            }
            return new RunEventAck(
                    batch.events().stream().map(RunEvent::sourceSeq).toList(), 43L);
        });

        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        RunEventPublisher publisher = new RunEventPublisher(
                control, new SimpleMeterRegistry(), scheduler, UUID.randomUUID(), 7L, "runtime-test");
        scheduler.shutdownNow();
        try {
            publisher.emit("run.started", java.util.Map.of("runId", "r"));
            publisher.emit("assistant.delta", java.util.Map.of("delta", "a"));
            Thread sender = new Thread(() -> publisher.flushAndAwait(Duration.ofSeconds(2)));
            sender.start();
            assertThat(firstRequestStarted.await(2, TimeUnit.SECONDS)).isTrue();
            publisher.emit("assistant.delta", java.util.Map.of("delta", "b"));
            secondEventQueued.countDown();
            sender.join(3_000);

            assertThat(publisher.flushAndAwait(Duration.ofSeconds(2))).isTrue();
            assertThat(publisher.pendingCount()).isZero();
            assertThat(batches).hasSize(2);
            assertThat(batches.get(0).events()).extracting(RunEvent::sourceSeq).containsExactly(1L, 2L);
            assertThat(batches.get(1).events()).extracting(RunEvent::sourceSeq).containsExactly(3L);
        } finally {
            secondEventQueued.countDown();
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
