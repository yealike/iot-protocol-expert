package top.yealike.pep.runtime.execution;

import io.agentscope.core.ReActAgent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.Disposable;
import top.yealike.pep.runtime.contract.RunClaimResponse;
import top.yealike.pep.runtime.contract.RunDispatchReceipt;
import top.yealike.pep.runtime.contract.RunDispatchRequest;
import top.yealike.pep.runtime.contract.RuntimeRunStatus;
import top.yealike.pep.runtime.prepare.PreparedRelease;

/** 单 Run 的运行时句柄；只保存内存状态，持久事实仍归 Control。 */
public final class RunExecution {

    private final RunDispatchRequest dispatch;
    private final PreparedRelease release;
    private final String instanceId;
    private final Instant acceptedAt = Instant.now();
    private final AtomicReference<RunGuard> guard = new AtomicReference<>();
    private final AtomicReference<RunEventPublisher> publisher = new AtomicReference<>();
    private final AtomicReference<Disposable> subscription = new AtomicReference<>();
    private final AtomicReference<ReActAgent> agent = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
    private final AtomicReference<Future<?>> future = new AtomicReference<>();
    private final AtomicReference<String> status = new AtomicReference<>("QUEUED");
    private boolean workerStarted;
    private boolean terminal;
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong modelCalls = new AtomicLong();
    private final CopyOnWriteArrayList<String> warnings = new CopyOnWriteArrayList<>();

    private volatile RunDispatchReceipt receipt;
    private volatile RunClaimResponse claim;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile String errorCode;
    private volatile String errorMessage;
    private volatile boolean timedOut;
    private volatile int maxIterations;

    public RunExecution(RunDispatchRequest dispatch, PreparedRelease release, String instanceId) {
        this.dispatch = dispatch;
        this.release = release;
        this.instanceId = instanceId;
    }

    public RunDispatchRequest dispatch() {
        return dispatch;
    }

    public PreparedRelease release() {
        return release;
    }

    public String instanceId() {
        return instanceId;
    }

    public Instant acceptedAt() {
        return acceptedAt;
    }

    public RunGuard guard() {
        return guard.get();
    }

    public void guard(RunGuard value) {
        guard.set(value);
    }

    public RunEventPublisher publisher() {
        return publisher.get();
    }

    public void publisher(RunEventPublisher value) {
        publisher.set(value);
    }

    public Disposable subscription() {
        return subscription.get();
    }

    public void subscription(Disposable value) {
        subscription.set(value);
    }

    public ReActAgent agent() {
        return agent.get();
    }

    public void agent(ReActAgent value) {
        agent.set(value);
    }

    public ScheduledFuture<?> heartbeat() {
        return heartbeat.get();
    }

    public void heartbeat(ScheduledFuture<?> value) {
        heartbeat.set(value);
    }

    public Future<?> future() {
        return future.get();
    }

    public void future(Future<?> value) {
        future.set(value);
    }

    public String status() {
        return status.get();
    }

    public void status(String value) {
        status.set(value);
    }

    public RunDispatchReceipt receipt() {
        return receipt;
    }

    public void receipt(RunDispatchReceipt value) {
        receipt = value;
    }

    public RunClaimResponse claim() {
        return claim;
    }

    public void claim(RunClaimResponse value) {
        claim = value;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public void startedAt(Instant value) {
        startedAt = value;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public void finishedAt(Instant value) {
        finishedAt = value;
    }

    public String errorCode() {
        return errorCode;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public void error(String code, String message) {
        errorCode = code;
        errorMessage = message;
    }

    public boolean timedOut() {
        return timedOut;
    }

    public void timedOut(boolean value) {
        timedOut = value;
    }

    public int maxIterations() {
        return maxIterations;
    }

    public void maxIterations(int value) {
        maxIterations = value;
    }

    public synchronized boolean markTerminal() {
        if (terminal) {
            return false;
        }
        terminal = true;
        return true;
    }

    public synchronized boolean markWorkerStarted() {
        if (terminal || workerStarted) {
            return false;
        }
        workerStarted = true;
        return true;
    }

    public synchronized boolean workerStarted() {
        return workerStarted;
    }

    public synchronized boolean tryCancelQueued() {
        if (terminal || workerStarted) {
            return false;
        }
        terminal = true;
        return true;
    }

    public synchronized boolean terminal() {
        return terminal;
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public void warning(String message) {
        if (message != null && !message.isBlank()) {
            warnings.add(message);
        }
    }

    public void addWarnings(List<String> values) {
        if (values != null) {
            values.forEach(this::warning);
        }
    }

    public void addUsage(int input, int output) {
        inputTokens.addAndGet(Math.max(0, input));
        outputTokens.addAndGet(Math.max(0, output));
        modelCalls.incrementAndGet();
    }

    public long inputTokens() {
        return inputTokens.get();
    }

    public long outputTokens() {
        return outputTokens.get();
    }

    public long modelCalls() {
        return modelCalls.get();
    }

    public RuntimeRunStatus view(long executionToken) {
        return new RuntimeRunStatus(
                dispatch.runId(),
                executionToken,
                status.get(),
                instanceId,
                startedAt,
                finishedAt,
                errorCode,
                errorMessage);
    }

    public UUID runId() {
        return dispatch.runId();
    }

    public long executionToken() {
        return dispatch.executionToken();
    }
}
