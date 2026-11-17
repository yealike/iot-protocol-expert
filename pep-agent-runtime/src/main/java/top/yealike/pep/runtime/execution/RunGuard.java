package top.yealike.pep.runtime.execution;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 单 Run 的执行权与 deadline；工具调用和模型流共享同一取消标记。 */
public final class RunGuard {

    private final UUID runId;
    private final long executionToken;
    private final long authVersion;
    private final Instant deadline;
    private final int maxToolCalls;
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final Semaphore toolSlots = new Semaphore(2);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile String cancelReason;
    private volatile String cancelCode;

    public RunGuard(UUID runId, long executionToken, long authVersion, Instant deadline, int maxToolCalls) {
        this.runId = runId;
        this.executionToken = executionToken;
        this.authVersion = authVersion;
        this.deadline = deadline;
        this.maxToolCalls = Math.max(1, maxToolCalls);
    }

    public UUID runId() {
        return runId;
    }

    public long executionToken() {
        return executionToken;
    }

    public long authVersion() {
        return authVersion;
    }

    public Instant deadline() {
        return deadline;
    }

    public boolean cancelled() {
        return cancelled.get();
    }

    public String cancelReason() {
        return cancelReason;
    }

    public String cancelCode() {
        return cancelCode;
    }

    public void cancel(String reason) {
        cancel("CANCELLED", reason);
    }

    public void cancel(String code, String reason) {
        if (cancelled.compareAndSet(false, true)) {
            cancelCode = code;
            cancelReason = reason;
        }
    }

    public int toolCalls() {
        return toolCalls.get();
    }

    public void reserveToolCall() {
        ensureActive();
        int count = toolCalls.incrementAndGet();
        if (count > maxToolCalls) {
            throw new RunAbortedException("TOOL_BUDGET_EXCEEDED", "工具调用次数超过本次 Run 预算");
        }
    }

    public void acquireToolSlot() {
        ensureActive();
        long waitMillis =
                Math.max(1L, java.time.Duration.between(Instant.now(), deadline).toMillis());
        try {
            if (!toolSlots.tryAcquire(Math.min(waitMillis, 10_000L), TimeUnit.MILLISECONDS)) {
                throw new RunAbortedException("TOOL_BUSY", "工具并发已满，请稍后重试");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RunAbortedException("CANCELLED", "工具调用被中断");
        }
    }

    public void releaseToolSlot() {
        toolSlots.release();
    }

    public void ensureActive() {
        if (cancelled.get()) {
            throw new RunAbortedException(
                    cancelCode == null ? "CANCELLED" : cancelCode, cancelReason == null ? "任务已停止" : cancelReason);
        }
        if (Instant.now().isAfter(deadline)) {
            throw new RunAbortedException("TIMED_OUT", "任务已超过执行截止时间");
        }
    }

    public static final class RunAbortedException extends RuntimeException {
        private final String code;

        public RunAbortedException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
