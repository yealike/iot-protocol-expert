package top.yealike.pep.runtime.execution;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import top.yealike.pep.runtime.control.ControlClient;
import top.yealike.pep.runtime.control.ControlException;
import top.yealike.pep.runtime.contract.ErrorCodes;
import top.yealike.pep.runtime.contract.RunEvent;
import top.yealike.pep.runtime.contract.RunEventAck;
import top.yealike.pep.runtime.contract.RunEventBatch;

/**
 * 单 Run 事件桥。
 *
 * <p>sourceSeq 从 1 单调递增；每个批次最多 32 条/64 KiB。未 ACK 队列超过 256 条/1 MiB 时暂停
 * 事件生产者，持续 10 秒仍无法恢复则终止本地执行，避免无限堆内存。
 */
public final class RunEventPublisher implements RunEventSink {

    private static final Logger log = LoggerFactory.getLogger(RunEventPublisher.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final int MAX_BATCH_EVENTS = 32;
    private static final int MAX_BATCH_BYTES = 64 * 1024;
    private static final int MAX_PENDING_EVENTS = 256;
    private static final long MAX_PENDING_BYTES = 1024L * 1024L;
    private static final Duration DELIVERY_FAILURE_LIMIT = Duration.ofSeconds(10);

    private final ControlClient control;
    private final MeterRegistry metrics;
    private final UUID runId;
    private final long executionToken;
    private final String instanceId;
    private final ArrayDeque<QueuedEvent> pending = new ArrayDeque<>();
    private final AtomicBoolean flushing = new AtomicBoolean();
    private final ScheduledFuture<?> scheduled;
    private final Timer ackTimer;
    private final Object monitor = new Object();

    private long nextSeq = 1L;
    private long pendingBytes;
    private Instant deliveryFailureSince;
    private Instant retryAfter;
    private int consecutiveFailures;
    private volatile boolean closed;

    public RunEventPublisher(
            ControlClient control,
            MeterRegistry metrics,
            ScheduledThreadPoolExecutor scheduler,
            UUID runId,
            long executionToken,
            String instanceId) {
        this.control = control;
        this.metrics = metrics;
        this.runId = runId;
        this.executionToken = executionToken;
        this.instanceId = instanceId;
        this.ackTimer = Timer.builder("event_ack_latency").register(metrics);
        this.scheduled = scheduler.scheduleWithFixedDelay(this::flushSafely, 200L, 200L, TimeUnit.MILLISECONDS);
    }

    @Override
    public void emit(String type, Map<String, Object> payload) {
        RunEvent event;
        byte[] encoded;
        boolean flushNow;
        synchronized (monitor) {
            ensureDeliverable();
            event = new RunEvent(type, payload, nextSeq++, UUID.randomUUID(), Instant.now());
            encoded = encode(event);
            if (encoded.length > MAX_BATCH_BYTES) {
                throw new RunGuard.RunAbortedException("EVENT_TOO_LARGE", "单条运行事件超过 64 KiB，已拒绝发送");
            }
            awaitCapacity(encoded.length);
            pending.addLast(new QueuedEvent(event, encoded.length));
            pendingBytes += encoded.length;
            flushNow = pending.size() >= MAX_BATCH_EVENTS;
            monitor.notifyAll();
        }
        if (flushNow) {
            flushSafely();
        }
    }

    public boolean flushAndAwait(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            flushOnce();
            synchronized (monitor) {
                if (pending.isEmpty()) {
                    return true;
                }
            }
            sleep(50L);
        }
        synchronized (monitor) {
            return pending.isEmpty();
        }
    }

    public boolean pending() {
        synchronized (monitor) {
            return !pending.isEmpty();
        }
    }

    public int pendingCount() {
        synchronized (monitor) {
            return pending.size();
        }
    }

    public void close() {
        closed = true;
        scheduled.cancel(false);
        flushAndAwait(Duration.ofSeconds(3));
    }

    private void flushSafely() {
        try {
            flushOnce();
        } catch (RuntimeException exception) {
            log.debug(
                    "run event flush skipped: run={} reason={}",
                    runId,
                    exception.getClass().getSimpleName());
        }
    }

    private void flushOnce() {
        if (closed && !pending()) {
            return;
        }
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        List<QueuedEvent> batch = List.of();
        try {
            if (retryAfter != null && Instant.now().isBefore(retryAfter)) {
                return;
            }
            int bytes = 0;
            synchronized (monitor) {
                if (pending.isEmpty()) {
                    return;
                }
                batch = takeBatch(bytes);
            }
            long started = System.nanoTime();
            RunEventAck ack = control.events(new RunEventBatch(
                    runId, executionToken, instanceId, UUID.randomUUID().toString(), events(batch)));
            if (ack == null) {
                deliveryFailed("EMPTY_EVENT_ACK");
                return;
            }
            acknowledge(batch, ack);
            ackTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            synchronized (monitor) {
                deliveryFailureSince = null;
                retryAfter = null;
                consecutiveFailures = 0;
            }
        } catch (ControlException exception) {
            deliveryFailed(exception.code());
        } catch (RuntimeException exception) {
            deliveryFailed("EVENT_DELIVERY_FAILED");
        } finally {
            flushing.set(false);
        }
    }

    private List<QueuedEvent> takeBatch(int initialBytes) {
        List<QueuedEvent> batch = new ArrayList<>(MAX_BATCH_EVENTS);
        int bytes = initialBytes;
        for (QueuedEvent queued : pending) {
            if (batch.size() >= MAX_BATCH_EVENTS || (bytes + queued.bytes() > MAX_BATCH_BYTES && !batch.isEmpty())) {
                break;
            }
            batch.add(queued);
            bytes += queued.bytes();
        }
        return batch;
    }

    private void acknowledge(List<QueuedEvent> batch, RunEventAck ack) {
        if (ack == null) {
            return;
        }
        Set<Long> accepted = new HashSet<>(ack.acceptedSourceSeq());
        if (accepted.isEmpty()) {
            log.warn(
                    "run event ack contains no source sequence: run={} batch={}",
                    runId,
                    batch.stream().map(item -> item.event().sourceSeq()).toList());
            return;
        }
        Set<Long> batchSequences =
                batch.stream().map(item -> item.event().sourceSeq()).collect(Collectors.toUnmodifiableSet());
        if (!batchSequences.containsAll(accepted)) {
            throw new ControlException(409, "EVENT_ACK_CONFLICT", "ACK 确认的 sourceSeq 不属于本次发送批次");
        }
        if (!accepted.containsAll(batchSequences)) {
            throw new ControlException(409, "EVENT_ACK_INCOMPLETE", "ACK 未确认完整发送批次");
        }
        synchronized (monitor) {
            for (QueuedEvent queued : batch) {
                if (!pending.contains(queued)) {
                    continue;
                }
                pending.removeFirst();
                pendingBytes -= queued.bytes();
                if (pendingBytes < 0) {
                    pendingBytes = 0;
                }
            }
            monitor.notifyAll();
        }
    }

    private void deliveryFailed(String code) {
        synchronized (monitor) {
            if (deliveryFailureSince == null) {
                deliveryFailureSince = Instant.now();
            }
            consecutiveFailures++;
            long backoffMillis = Math.min(5_000L, 200L * (1L << Math.min(consecutiveFailures, 5)));
            retryAfter = Instant.now().plusMillis(backoffMillis);
            monitor.notifyAll();
        }
        metrics.counter("run_event_delivery_failures_total", "code", safeCode(code))
                .increment();
        log.warn("run event delivery failed: run={} code={} pending={}", runId, code, pendingCount());
    }

    private void ensureDeliverable() {
        if (deliveryFailureSince != null
                && Duration.between(deliveryFailureSince, Instant.now()).compareTo(DELIVERY_FAILURE_LIMIT) > 0) {
            throw new RunGuard.RunAbortedException(ErrorCodes.EXECUTION_LOST, "事件通道超过 10 秒不可用，已停止本地执行");
        }
    }

    private void awaitCapacity(int bytes) {
        if (bytes > MAX_PENDING_BYTES) {
            throw new RunGuard.RunAbortedException("EVENT_TOO_LARGE", "单条运行事件超过未 ACK 队列上限");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (pending.size() >= MAX_PENDING_EVENTS || pendingBytes + bytes > MAX_PENDING_BYTES) {
            ensureDeliverable();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new RunGuard.RunAbortedException("EVENT_BUFFER_OVERFLOW", "未确认事件队列超过 256 条/1 MiB，暂停模型流 10 秒仍未恢复");
            }
            try {
                monitor.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RunGuard.RunAbortedException("CANCELLED", "等待事件 ACK 时被中断");
            }
        }
    }

    private static List<RunEvent> events(List<QueuedEvent> batch) {
        return batch.stream().map(QueuedEvent::event).toList();
    }

    private static byte[] encode(RunEvent event) {
        try {
            return JSON.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException exception) {
            return String.valueOf(event).getBytes(StandardCharsets.UTF_8);
        }
    }

    private static String safeCode(String code) {
        return code == null || code.isBlank() ? "EVENT_DELIVERY_FAILED" : code;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private record QueuedEvent(RunEvent event, int bytes) {}
}
