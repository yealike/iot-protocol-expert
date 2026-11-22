package top.yealike.pep.control.runs;

import jakarta.annotation.PostConstruct;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import top.yealike.pep.control.access.policy.PolicyService;
import top.yealike.pep.control.agents.AgentService;
import top.yealike.pep.control.agents.domain.AgentRelease;
import top.yealike.pep.control.config.properties.AiProperties;
import top.yealike.pep.control.config.properties.PlatformProperties;
import top.yealike.pep.control.integration.runtime.RuntimeDispatchClient;
import top.yealike.pep.control.integration.runtime.dto.DispatchResult;
import top.yealike.pep.control.integration.runtime.dto.RuntimeTarget;
import top.yealike.pep.control.observability.AiObservability;
import top.yealike.pep.control.runs.domain.Run;
import top.yealike.pep.runtime.contract.ErrorCodes;
import top.yealike.pep.runtime.contract.RunDispatchRequest;
import top.yealike.pep.runtime.contract.UserIdentity;

/**
 * Run 远程派发器。
 *
 * <p>Control 只维护队列、租约与终态；模型和工具在 Runtime 执行。认领用 QUEUED→DISPATCHING 条件更新，
 * 每次认领生成严格递增 executionToken，Runtime 以 (runId,token) fencing。
 */
@Service
public class RunScheduler {

    private static final Logger log = LoggerFactory.getLogger(RunScheduler.class);

    private final RunRepository runs;
    private final AgentService agents;
    private final PolicyService policy;
    private final RunEventService events;
    private final RuntimeDispatchClient runtime;
    private final PlatformProperties platform;
    private final AiProperties properties;
    private final AiObservability observability;
    private final AtomicInteger dispatching = new AtomicInteger();
    private final String nodeId =
            "ai-control-" + ManagementFactory.getRuntimeMXBean().getName() + "-"
                    + UUID.randomUUID().toString().substring(0, 8);

    public RunScheduler(
            RunRepository runs,
            AgentService agents,
            PolicyService policy,
            RunEventService events,
            RuntimeDispatchClient runtime,
            PlatformProperties platform,
            AiProperties properties,
            AiObservability observability) {
        this.runs = runs;
        this.agents = agents;
        this.policy = policy;
        this.events = events;
        this.runtime = runtime;
        this.platform = platform;
        this.properties = properties;
        this.observability = observability;
    }

    @PostConstruct
    void start() {
        policy.reload();
        log.info(
                "ai run dispatcher started: node={} control={}",
                nodeId,
                platform.registry().controlServiceName());
    }

    @Scheduled(fixedDelay = 700L, initialDelay = 2_000L)
    public void tick() {
        observability.observeSupplier("ai run dispatch", () -> {
            try {
                dispatchNew();
                retryDispatches();
                reapExpiredLeases();
                convergeCancellations();
            } catch (RuntimeException exception) {
                log.warn("ai run dispatch skipped: {}", safeMessage(exception));
            }
            return null;
        });
    }

    private synchronized void dispatchNew() {
        int max = Math.max(1, properties.run().maxConcurrent());
        int leaseSeconds = leaseSeconds();
        while (dispatching.get() < max) {
            var claimed = runs.claimNextForDispatch(
                    nodeId,
                    leaseSeconds,
                    Instant.now().plusMillis(properties.run().timeoutMs()),
                    platform.runtime().perUserInFlight());
            if (claimed.isEmpty()) {
                return;
            }
            Run run = claimed.get();
            dispatching.incrementAndGet();
            try {
                if (run.dispatchAttempts() == 1) {
                    events.publish(run.conversationId(), run.id(), "run.dispatching", Map.of());
                }
                dispatch(run, false, run.dispatchAttempts());
            } catch (RuntimeException exception) {
                log.error("run dispatch failed: run={}", run.id(), exception);
                fail(run, "DISPATCH_ERROR", "派发失败，请稍后重试");
            } finally {
                dispatching.decrementAndGet();
            }
        }
    }

    private void dispatch(Run run, boolean retry, int attempt) {
        AgentRelease release = run.agentReleaseId() == null
                ? agents.requirePublishedRelease(run.agentKey(), run.agentVersion())
                : agents.release(run.agentReleaseId());
        if (!"ACTIVE".equals(release.state())) {
            fail(run, ErrorCodes.RELEASE_NOT_ACTIVE, "Agent 版本未激活，不能执行");
            return;
        }
        UserIdentity identity = new UserIdentity(
                run.actor(),
                run.actor(),
                run.actorRoles() == null ? List.of() : run.actorRoles(),
                List.of(),
                run.actorAuthVersion() == null ? 0 : run.actorAuthVersion());
        if (!policy.canUseAgent(identity, run.agentKey()).allowed()) {
            fail(run, "AGENT_DENIED", "当前账号已无权使用该 Agent");
            return;
        }
        if (attempt > maxDispatchAttempts()) {
            fail(run, ErrorCodes.NO_HEALTHY_RUNTIME, "多次派发仍无可用 Runtime");
            return;
        }

        List<RuntimeTarget> targets = runtime.targets();
        if (targets.isEmpty()) {
            releaseOrFail(run, attempt, ErrorCodes.NO_HEALTHY_RUNTIME, "当前没有可用的 Agent Runtime");
            return;
        }
        RuntimeTarget target = retry
                ? targetFor(run, targets)
                : targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
        if (target == null) {
            releaseOrFail(run, attempt, ErrorCodes.NO_HEALTHY_RUNTIME, "原执行 Runtime 已不可发现");
            return;
        }
        if (!retry && !runs.bindDispatchTarget(run.id(), run.executionToken(), target.instanceId(), leaseSeconds())) {
            log.warn("run dispatch binding lost: run={} token={}", run.id(), run.executionToken());
            return;
        }

        var request = new RunDispatchRequest(
                run.id(),
                release.id(),
                release.manifestDigest(),
                run.executionToken(),
                release.agentKey(),
                release.version(),
                run.conversationId(),
                run.actor(),
                run.actorAuthVersion() == null ? 0 : run.actorAuthVersion(),
                run.deadlineAt(),
                platform.security().internalServiceName(),
                run.traceContext());
        DispatchResult result = runtime.dispatch(target, release.agentKey(), release.version(), request);
        if (result.accepted()) {
            String instanceId = result.receipt() == null || result.receipt().instanceId() == null
                    ? target.instanceId()
                    : result.receipt().instanceId();
            runs.markRunning(run.id(), run.executionToken(), instanceId, leaseSeconds());
            log.info(
                    "run dispatched: run={} agent={} version={} instance={} token={}",
                    run.id(),
                    release.agentKey(),
                    release.version(),
                    instanceId,
                    run.executionToken());
            return;
        }
        if (result.status() == 429) {
            releaseOrFail(run, attempt, ErrorCodes.RUN_BUSY, "Runtime 正忙，请稍后重试");
            return;
        }
        if (ErrorCodes.SCHEMA_DRIFT.equals(result.code())) {
            String reason = result.message() == null ? "MCP 工具结构与发布版本不一致" : result.message();
            fail(run, result.code(), reason + "。请联系管理员核对 MCP 工具并发布新的 Agent 版本。");
            return;
        }
        if (result.status() >= 400 && result.status() < 500 && result.status() != 408 && result.status() != 409) {
            fail(run, result.code() == null ? "DISPATCH_REJECTED" : result.code(), "Runtime 拒绝接收任务，请联系管理员");
            return;
        }
        var status = runtime.status(target, run.id(), run.executionToken());
        if (status != null) {
            runs.markRunning(run.id(), run.executionToken(), status.instanceId(), leaseSeconds());
            log.info("run dispatch recovered by receipt: run={} status={}", run.id(), status.status());
            return;
        }
        if (attempt >= maxDispatchAttempts()) {
            String code = result.code() == null ? ErrorCodes.NO_HEALTHY_RUNTIME : result.code();
            String message = ErrorCodes.RELEASE_NOT_ACTIVE.equals(code)
                    ? "此 Agent 版本尚未在 Runtime 就绪，请联系管理员检查发布状态"
                    : "运行服务未能接收任务，请稍后重试";
            fail(run, code, message);
            return;
        }
        runs.deferDispatch(
                run.id(), run.executionToken(), Instant.now().plusMillis(backoffMillis(attempt)), leaseSeconds());
    }

    private void retryDispatches() {
        for (Run run : runs.dispatchRetries(10)) {
            if (!runs.claimDispatchRetry(run, leaseSeconds())) {
                continue;
            }
            int attempt = run.dispatchAttempts() + 1;
            try {
                dispatch(run, true, attempt);
            } catch (RuntimeException exception) {
                log.warn("run dispatch retry failed: run={}", run.id(), exception);
                if (attempt >= maxDispatchAttempts()) {
                    fail(run, "DISPATCH_ERROR", "派发失败，请稍后重试");
                } else {
                    runs.deferDispatch(
                            run.id(), run.executionToken(), Instant.now().plusSeconds(2), leaseSeconds());
                }
            }
        }
    }

    private void reapExpiredLeases() {
        Instant now = Instant.now();
        for (Run run : runs.expiredLeases(20)) {
            boolean deadlineExpired =
                    run.deadlineAt() != null && !run.deadlineAt().isAfter(now);
            if (!deadlineExpired && RunStatus.DISPATCHING.equals(run.status()) && run.dispatchNextAttemptAt() != null) {
                continue;
            }
            String code = deadlineExpired ? "RUN_TIMED_OUT" : ErrorCodes.EXECUTION_LOST;
            String message = deadlineExpired ? "Run 已超过执行截止时间，由 Control 终结" : "执行租约已过期，Runtime 未继续回写进度";
            String terminal =
                    runs.terminateExpired(run.id(), run.executionToken(), run.runtimeInstanceId(), code, message);
            if (terminal == null) {
                continue;
            }
            if (RunStatus.CANCELLED.equals(terminal)) {
                events.publish(
                        run.conversationId(),
                        run.id(),
                        "run.cancelled",
                        Map.of("status", RunStatus.CANCELLED, "note", "Runtime 取消确认超时，由 Control 终结"));
                log.warn("run cancellation converged by expiry: run={} instance={}", run.id(), run.runtimeInstanceId());
            } else {
                events.publish(
                        run.conversationId(),
                        run.id(),
                        "run.failed",
                        Map.of("runId", run.id().toString(), "errorCode", code, "message", message));
                log.warn(
                        "run expired and terminated: run={} instance={} code={}",
                        run.id(),
                        run.runtimeInstanceId(),
                        code);
            }
        }
    }

    private void convergeCancellations() {
        for (Run run : runs.cancelPending(10, 20)) {
            boolean cancelled = runs.markCancelled(run.id(), run.executionToken(), run.runtimeInstanceId(), Map.of());
            if (cancelled) {
                events.publish(
                        run.conversationId(),
                        run.id(),
                        "run.cancelled",
                        Map.of("status", RunStatus.CANCELLED, "note", "Runtime 取消确认超时，由 Control 终结"));
            }
        }
    }

    private void releaseOrFail(Run run, int attempt, String code, String message) {
        if (attempt >= maxDispatchAttempts()) {
            fail(run, code, message);
            return;
        }
        boolean released = runs.releaseDispatch(
                run.id(), run.executionToken(), Instant.now().plusMillis(backoffMillis(attempt)));
        if (!released) {
            log.warn("run release skipped: run={} token={}", run.id(), run.executionToken());
        }
    }

    private void fail(Run run, String code, String message) {
        if (runs.failDispatch(run.id(), run.executionToken(), code, message)) {
            events.publish(
                    run.conversationId(),
                    run.id(),
                    "run.failed",
                    Map.of("runId", run.id().toString(), "errorCode", code, "message", message));
            log.warn("run dispatch rejected: run={} code={}", run.id(), code);
        }
    }

    private RuntimeTarget targetFor(Run run, List<RuntimeTarget> targets) {
        String instanceId = run.runtimeInstanceId();
        if (instanceId == null || instanceId.isBlank()) {
            return targets.size() == 1 ? targets.getFirst() : null;
        }
        return targets.stream()
                .filter(item -> item.matchesInstance(instanceId))
                .findFirst()
                .orElse(null);
    }

    private int leaseSeconds() {
        return Math.max(30, platform.runtime().leaseSeconds());
    }

    private int maxDispatchAttempts() {
        return Math.max(1, platform.runtime().maxDispatchAttempts());
    }

    private static long backoffMillis(int attempt) {
        long seconds = 1L << Math.min(Math.max(attempt - 1, 0), 3);
        return seconds * 1000L + ThreadLocalRandom.current().nextLong(400L);
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
