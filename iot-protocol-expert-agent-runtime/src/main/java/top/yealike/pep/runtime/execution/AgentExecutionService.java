package top.yealike.pep.runtime.execution;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.model.ChatUsage;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import top.yealike.pep.runtime.api.RunRejectedException;
import top.yealike.pep.runtime.config.properties.RuntimeProperties;
import top.yealike.pep.runtime.contract.AgentManifest;
import top.yealike.pep.runtime.contract.CredentialResolveRequest;
import top.yealike.pep.runtime.contract.CredentialResolveResponse;
import top.yealike.pep.runtime.contract.ErrorCodes;
import top.yealike.pep.runtime.contract.RunClaimRequest;
import top.yealike.pep.runtime.contract.RunClaimResponse;
import top.yealike.pep.runtime.contract.RunCompletionRequest;
import top.yealike.pep.runtime.contract.RunContext;
import top.yealike.pep.runtime.contract.RunDispatchReceipt;
import top.yealike.pep.runtime.contract.RunDispatchRequest;
import top.yealike.pep.runtime.contract.RunTerminalStatus;
import top.yealike.pep.runtime.contract.RuntimeRunStatus;
import top.yealike.pep.runtime.control.ControlClient;
import top.yealike.pep.runtime.control.ControlException;
import top.yealike.pep.runtime.execution.domain.ExecutionOutcome;
import top.yealike.pep.runtime.execution.domain.ModelQuotaFailure;
import top.yealike.pep.runtime.execution.domain.ToolCallTiming;
import top.yealike.pep.runtime.observability.RuntimeObservability;
import top.yealike.pep.runtime.prepare.PreparedReleaseRegistry;
import top.yealike.pep.runtime.prepare.domain.PreparedRelease;
import top.yealike.pep.runtime.prepare.domain.PreparedTool;
import top.yealike.pep.runtime.registry.NacosRuntimeRegistry;

/**
 * Run 执行编排。
 *
 * <p>Control 保留状态、事件游标和聊天历史；Runtime 只负责执行、事件回写与本地终态收敛。
 * 所有模型/工具/上下文调用都受 claim、heartbeat 和 deadline 约束。
 */
@Service
public class AgentExecutionService {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutionService.class);
    private static final int MAX_FINAL_ANSWER_CHARS = 60_000;
    private static final int DELTA_CHUNK_CHARS = 4_000;
    private static final int MAX_TOOL_EVIDENCE_CHARS = 8_000;

    private final RuntimeProperties properties;
    private final ControlClient control;
    private final PreparedReleaseRegistry releases;
    private final RunExecutionRegistry executions;
    private final RunAdmission admission;
    private final AgentRuntimeFactory agentFactory;
    private final RunMessageMapper messageMapper;
    private final NacosRuntimeRegistry nacos;
    private final ExecutorService runExecutor;
    private final ScheduledThreadPoolExecutor heartbeatScheduler;
    private final ScheduledThreadPoolExecutor eventScheduler;
    private final MeterRegistry metrics;
    private final RuntimeObservability observability;
    private final AtomicInteger activeRuns = new AtomicInteger();

    public AgentExecutionService(
            RuntimeProperties properties,
            ControlClient control,
            PreparedReleaseRegistry releases,
            RunExecutionRegistry executions,
            RunAdmission admission,
            AgentRuntimeFactory agentFactory,
            RunMessageMapper messageMapper,
            NacosRuntimeRegistry nacos,
            @Qualifier("runtimeRunExecutor") ExecutorService runExecutor,
            @Qualifier("runtimeHeartbeatScheduler") ScheduledThreadPoolExecutor heartbeatScheduler,
            @Qualifier("runtimeEventScheduler") ScheduledThreadPoolExecutor eventScheduler,
            MeterRegistry metrics,
            RuntimeObservability observability) {
        this.properties = properties;
        this.control = control;
        this.releases = releases;
        this.executions = executions;
        this.admission = admission;
        this.agentFactory = agentFactory;
        this.messageMapper = messageMapper;
        this.nacos = nacos;
        this.runExecutor = runExecutor;
        this.heartbeatScheduler = heartbeatScheduler;
        this.eventScheduler = eventScheduler;
        this.metrics = metrics;
        this.observability = observability;
        Gauge.builder("run_active", activeRuns, AtomicInteger::get).register(metrics);
    }

    public RunDispatchReceipt dispatch(String agentKey, String version, RunDispatchRequest request) {
        validateDispatch(agentKey, version, request);
        PreparedRelease release = releases.find(request.releaseId()).orElseGet(() -> {
            var failure = releases.schemaFailure(request.releaseId(), request.manifestDigest());
            if (failure.isPresent()) {
                throw new RunRejectedException(422, ErrorCodes.SCHEMA_DRIFT, failure.get(), null);
            }
            throw new RunRejectedException(409, ErrorCodes.RELEASE_NOT_ACTIVE, "该发布尚未在当前 Runtime 准备完成", null);
        });
        verifyRelease(agentKey, version, request, release);

        Object lock = executions.dispatchLock(request.runId());
        synchronized (lock) {
            RunExecution existing = executions.find(request.runId()).orElse(null);
            if (existing != null) {
                if (existing.executionToken() == request.executionToken()) {
                    return existing.receipt();
                }
                throw new RunRejectedException(409, ErrorCodes.STALE_EXECUTION_TOKEN, "同一 Run 已存在其他执行令牌", null);
            }
            if (!admission.tryEnter()) {
                metrics.counter("dispatch_failure_total", "code", ErrorCodes.RUN_BUSY)
                        .increment();
                throw new RunRejectedException(429, ErrorCodes.RUN_BUSY, "Runtime 并发与等待队列已满", 1L);
            }
            RunExecution execution = new RunExecution(request, release, nacos.instanceId());
            boolean registered = false;
            try {
                RunClaimResponse claim = control.claim(new RunClaimRequest(
                        request.runId(),
                        request.executionToken(),
                        nacos.instanceId(),
                        UUID.randomUUID().toString()));
                if (claim == null || !claim.allowed()) {
                    String code = claim == null ? ErrorCodes.EXECUTION_LOST : claimReasonCode(claim.reason());
                    throw new RunRejectedException(
                            409,
                            code,
                            claim == null || claim.reason() == null ? "Control 未授予执行权" : claim.reason(),
                            null);
                }
                if (claim.cancelRequired()) {
                    throw new RunRejectedException(409, "RUN_CANCELLED", "Control 已在派发时标记取消或撤销", null);
                }
                execution.claim(claim);
                int maxIterations = effectiveIterations(release, claim);
                int maxToolCalls = effectiveToolCalls(release, claim);
                Instant deadline = effectiveDeadline(request, claim, release);
                if (!deadline.isAfter(Instant.now())) {
                    throw new RunRejectedException(409, "RUN_TIMEOUT", "Run 已超过执行截止时间", null);
                }
                long authVersion = claim.authVersion() > 0 ? claim.authVersion() : request.authVersion();
                execution.guard(
                        new RunGuard(request.runId(), request.executionToken(), authVersion, deadline, maxToolCalls));
                execution.maxIterations(maxIterations);
                execution.publisher(new RunEventPublisher(
                        control,
                        metrics,
                        eventScheduler,
                        request.runId(),
                        request.executionToken(),
                        nacos.instanceId()));
                execution.receipt(new RunDispatchReceipt(
                        request.runId(), request.executionToken(), UUID.randomUUID(), nacos.instanceId(), true, null));
                // 租约从 Control 接受派发时开始计算，排队中的 Run 也必须持续心跳；否则等待
                // 超过 30 秒会被 Control 当作 EXECUTION_LOST。
                ScheduledFuture<?> heartbeat = startHeartbeat(execution);
                execution.heartbeat(heartbeat);
                RunExecution previous = executions.register(execution);
                if (previous != execution) {
                    heartbeat.cancel(false);
                    throw new RunRejectedException(409, ErrorCodes.STALE_EXECUTION_TOKEN, "同一 Run 已被其他执行实例接收", null);
                }
                registered = true;
                activeRuns.incrementAndGet();
                try {
                    Future<?> submitted = runExecutor.submit(() -> executeTraced(execution));
                    execution.future(submitted);
                } catch (RejectedExecutionException exception) {
                    boolean releaseAdmission = !execution.terminal();
                    if (releaseAdmission) {
                        activeRuns.decrementAndGet();
                    }
                    executions.remove(request.runId(), execution);
                    heartbeat.cancel(false);
                    closePublisher(execution);
                    if (releaseAdmission) {
                        admission.leave();
                    }
                    throw new RunRejectedException(429, ErrorCodes.RUN_BUSY, "Runtime 执行队列已满", 1L);
                } catch (RuntimeException exception) {
                    boolean releaseAdmission = !execution.terminal();
                    if (releaseAdmission) {
                        activeRuns.decrementAndGet();
                    }
                    executions.remove(request.runId(), execution);
                    heartbeat.cancel(false);
                    closePublisher(execution);
                    if (releaseAdmission) {
                        admission.leave();
                    }
                    throw exception;
                }
                log.info(
                        "run accepted: run={} agent={} version={} release={} token={}",
                        request.runId(),
                        agentKey,
                        version,
                        request.releaseId(),
                        request.executionToken());
                return execution.receipt();
            } catch (RunRejectedException exception) {
                if (!registered) {
                    closePublisher(execution);
                    admission.leave();
                    metrics.counter("dispatch_failure_total", "code", exception.code())
                            .increment();
                }
                throw exception;
            } catch (ControlException exception) {
                if (!registered) {
                    closePublisher(execution);
                    admission.leave();
                    metrics.counter("dispatch_failure_total", "code", exception.code())
                            .increment();
                }
                throw exception;
            } catch (RuntimeException exception) {
                if (!registered) {
                    closePublisher(execution);
                    admission.leave();
                    metrics.counter("dispatch_failure_total", "code", "DISPATCH_FAILED")
                            .increment();
                }
                throw exception;
            }
        }
    }

    public RuntimeRunStatus status(UUID runId, long executionToken) {
        RunExecution execution = executions
                .find(runId)
                .orElseThrow(() -> new RunRejectedException(404, "RUN_NOT_FOUND", "Runtime 本地没有该 Run", null));
        if (execution.executionToken() != executionToken) {
            throw new RunRejectedException(409, ErrorCodes.STALE_EXECUTION_TOKEN, "executionToken 与本地 Run 不匹配", null);
        }
        return execution.view(executionToken);
    }

    public RuntimeRunStatus cancel(UUID runId, long executionToken) {
        RunExecution execution = executions
                .find(runId)
                .orElseThrow(() -> new RunRejectedException(404, "RUN_NOT_FOUND", "Runtime 本地没有该 Run", null));
        if (execution.executionToken() != executionToken) {
            throw new RunRejectedException(409, ErrorCodes.STALE_EXECUTION_TOKEN, "executionToken 与本地 Run 不匹配", null);
        }
        if (execution.terminal()) {
            return execution.view(executionToken);
        }
        execution.status("CANCELLING");
        cancelLocal(execution, "CANCELLED", "用户取消 Run");
        return execution.view(executionToken);
    }

    private void executeTraced(RunExecution execution) {
        if (!execution.markWorkerStarted()) {
            return;
        }
        MDC.put("run_id", execution.runId().toString());
        MDC.put("component_key", execution.release().manifest().agentKey());
        try {
            observability
                    .restoreOrWrap(execution.dispatch().traceparent(), "agent.run", () -> execute(execution))
                    .run();
        } finally {
            MDC.remove("run_id");
            MDC.remove("component_key");
            MDC.remove("tool_alias");
        }
    }

    private void execute(RunExecution execution) {
        io.opentelemetry.api.trace.Span.current()
                .setAttribute("ai.run.id", execution.runId().toString());
        io.opentelemetry.api.trace.Span.current().setAttribute("ai.agent.release", String.valueOf(execution.release()));
        RunGuard guard = execution.guard();
        StringBuilder answer = new StringBuilder(2048);
        AtomicBoolean timedOut = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Map<String, ToolCallTiming> toolTimings = new ConcurrentHashMap<>();
        Map<String, ToolInvocationEvidence> toolEvidence = new ConcurrentHashMap<>();
        long startedNanos = System.nanoTime();
        try {
            if (guard.cancelled()) {
                return;
            }
            execution.status("RUNNING");
            execution.startedAt(Instant.now());
            execution.publisher().emit("run.started", startedPayload(execution));
            guard.ensureActive();
            RunContext context = control.context(execution.runId(), execution.executionToken());
            if (context == null) {
                throw new ControlException(502, "CONTEXT_UNAVAILABLE", "Control 未返回 Run 上下文");
            }
            List<Msg> messages = messageMapper.toMessages(context);
            if (messages.isEmpty()) {
                throw new ControlException(422, "CONTEXT_EMPTY", "Run 没有可执行的用户消息");
            }
            String modelSecret = resolveModelSecret(execution);
            guard.ensureActive();
            ReActAgent agent = agentFactory.build(
                    execution.release(), guard, execution.publisher(), modelSecret, execution.maxIterations());
            execution.agent(agent);

            RuntimeContext runtimeContext = RuntimeContext.builder()
                    .userId(actor(execution))
                    .sessionId(conversation(execution))
                    .put("runId", execution.runId().toString())
                    .put("agentKey", execution.release().manifest().agentKey())
                    .put("agentVersion", execution.release().manifest().version())
                    .build();
            Duration remaining = Duration.between(Instant.now(), guard.deadline());
            if (remaining.isNegative() || remaining.isZero()) {
                timedOut.set(true);
                throw new RunGuard.RunAbortedException("TIMED_OUT", "Run 已超过执行截止时间");
            }
            CountDownLatch completed = new CountDownLatch(1);
            Flux<AgentEvent> stream = agent.streamEvents(messages, runtimeContext)
                    .timeout(remaining)
                    .doFinally(signal -> completed.countDown());
            Disposable disposable = stream.subscribe(
                    event -> handleEvent(execution, event, answer, toolTimings, toolEvidence), failure::set, () -> {
                        // 由 doFinally 统一唤醒工作线程
                    });
            execution.subscription(disposable);
            guard.ensureActive();
            if (guard.cancelled()) {
                disposable.dispose();
            }
            long waitMillis = Math.max(1_000L, remaining.toMillis() + 2_000L);
            if (!completed.await(waitMillis, TimeUnit.MILLISECONDS)) {
                timedOut.set(true);
                disposable.dispose();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failure.set(new RunGuard.RunAbortedException("CANCELLED", "Run 执行被中断"));
        } catch (RunGuard.RunAbortedException exception) {
            failure.set(exception);
        } catch (ControlException exception) {
            failure.set(exception);
        } catch (Throwable throwable) {
            failure.set(throwable);
            log.error(
                    "run execution failed: run={} agent={}",
                    execution.runId(),
                    execution.release().manifest().agentKey(),
                    throwable);
        } finally {
            ScheduledFuture<?> heartbeat = execution.heartbeat();
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            ExecutionOutcome outcome = outcome(execution, failure.get(), timedOut.get(), answer.toString());
            failOpenToolCalls(execution, toolTimings, toolEvidence, outcome);
            finish(execution, outcome, answer.toString(), startedNanos);
        }
    }

    private void handleEvent(
            RunExecution execution,
            AgentEvent event,
            StringBuilder answer,
            Map<String, ToolCallTiming> timings,
            Map<String, ToolInvocationEvidence> evidenceByCall) {
        if (event instanceof TextBlockDeltaEvent deltaEvent) {
            String delta = deltaEvent.getDelta();
            if (delta == null || delta.isEmpty()) {
                return;
            }
            answer.append(delta);
            for (int offset = 0; offset < delta.length(); offset += DELTA_CHUNK_CHARS) {
                String chunk = delta.substring(offset, Math.min(delta.length(), offset + DELTA_CHUNK_CHARS));
                execution.publisher().emit("assistant.delta", Map.of("delta", chunk));
            }
            return;
        }
        if (event instanceof AgentResultEvent resultEvent) {
            if (answer.isEmpty() && resultEvent.getResult() != null) {
                String text = resultEvent.getResult().getTextContent();
                if (text != null && !text.isBlank()) {
                    answer.append(text);
                }
            }
            return;
        }
        if (event instanceof ModelCallEndEvent modelEvent) {
            ChatUsage usage = modelEvent.getUsage();
            if (usage != null) {
                execution.addUsage(usage.getInputTokens(), usage.getOutputTokens());
                io.opentelemetry.api.trace.Span.current()
                        .addEvent(
                                "gen_ai.usage",
                                io.opentelemetry.api.common.Attributes.builder()
                                        .put("gen_ai.usage.input_tokens", (long) usage.getInputTokens())
                                        .put("gen_ai.usage.output_tokens", (long) usage.getOutputTokens())
                                        .build());
            }
            return;
        }
        if (event instanceof ToolCallStartEvent startEvent) {
            String alias = startEvent.getToolCallName();
            PreparedTool tool = execution.release().toolByAlias().get(alias);
            Map<String, Object> payload = new LinkedHashMap<>();
            put(payload, "toolAlias", alias);
            put(payload, "toolCallId", startEvent.getToolCallId());
            if (tool != null) {
                put(payload, "componentKey", tool.mcpKey());
                put(payload, "componentVersion", tool.mcpVersion());
                put(payload, "originalName", tool.originalName());
            }
            timings.put(startEvent.getToolCallId(), new ToolCallTiming(alias, System.nanoTime()));
            evidenceByCall.putIfAbsent(
                    evidenceKey(startEvent.getToolCallId()), new ToolInvocationEvidence(MAX_TOOL_EVIDENCE_CHARS));
            execution.publisher().emit("tool.started", payload);
            return;
        }
        if (event instanceof ToolCallDeltaEvent callEvent) {
            evidence(callEvent.getToolCallId(), evidenceByCall).appendArguments(callEvent.getDelta());
            return;
        }
        if (event instanceof ToolCallEndEvent callEvent) {
            ToolInvocationEvidence evidence = evidenceByCall.get(evidenceKey(callEvent.getToolCallId()));
            if (evidence == null || !evidence.hasArguments()) {
                return;
            }
            ToolCallTiming timing = timings.get(callEvent.getToolCallId());
            Map<String, Object> payload = new LinkedHashMap<>();
            put(payload, "toolAlias", timing == null ? callEvent.getToolCallName() : timing.alias());
            put(payload, "toolCallId", callEvent.getToolCallId());
            payload.put("arguments", evidence.argumentsEvidence());
            execution.publisher().emit("tool.arguments", payload);
            return;
        }
        if (event instanceof ToolResultTextDeltaEvent resultEvent) {
            evidence(resultEvent.getToolCallId(), evidenceByCall).appendResult(resultEvent.getDelta());
            return;
        }
        if (event instanceof ToolResultDataDeltaEvent resultEvent) {
            evidence(resultEvent.getToolCallId(), evidenceByCall).appendData(resultEvent.getData());
            return;
        }
        if (event instanceof ToolResultEndEvent resultEvent) {
            ToolCallTiming timing = timings.remove(resultEvent.getToolCallId());
            String alias = timing == null ? resultEvent.getToolCallName() : timing.alias();
            ToolResultState state = resultEvent.getState();
            ToolInvocationEvidence evidence = evidenceByCall.remove(evidenceKey(resultEvent.getToolCallId()));
            Map<String, Object> payload = new LinkedHashMap<>();
            put(payload, "toolAlias", alias);
            put(payload, "toolCallId", resultEvent.getToolCallId());
            if (timing != null) {
                payload.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - timing.startedNanos()));
            }
            if (evidence != null && evidence.hasResult()) {
                payload.put("resultSummary", evidence.resultEvidence());
            }
            boolean success = state == ToolResultState.SUCCESS || state == null;
            if (success) {
                payload.put("decision", ToolAuditMetadata.ALLOW);
                execution.publisher().emit("tool.completed", payload);
            } else {
                put(payload, "errorCode", auditErrorCode(resultEvent, state));
                put(payload, "decision", auditDecision(resultEvent, state));
                execution.publisher().emit("tool.failed", payload);
                execution.warning("工具 " + alias + " 调用未成功");
            }
            return;
        }
        if (event instanceof ExceedMaxItersEvent maxItersEvent) {
            String message = "模型轮次达到上限 " + maxItersEvent.getMaxIters();
            execution.warning(message);
            execution.publisher().emit("run.warning", Map.of("code", "MAX_ITERATIONS", "message", message));
            return;
        }
        if (event instanceof AllToolsDeniedEvent) {
            String message = "模型请求的工具均被当前权限拒绝";
            execution.warning(message);
            execution.publisher().emit("run.warning", Map.of("code", ErrorCodes.TOOL_DENIED, "message", message));
        }
    }

    private static ToolInvocationEvidence evidence(
            String toolCallId, Map<String, ToolInvocationEvidence> evidenceByCall) {
        return evidenceByCall.computeIfAbsent(
                evidenceKey(toolCallId), ignored -> new ToolInvocationEvidence(MAX_TOOL_EVIDENCE_CHARS));
    }

    private static String evidenceKey(String toolCallId) {
        return toolCallId == null ? "" : toolCallId;
    }

    private void failOpenToolCalls(
            RunExecution execution,
            Map<String, ToolCallTiming> timings,
            Map<String, ToolInvocationEvidence> evidenceByCall,
            ExecutionOutcome outcome) {
        if (timings.isEmpty()) {
            return;
        }
        String errorCode = switch (outcome.status()) {
            case TIMED_OUT -> "TIMED_OUT";
            case CANCELLED -> "CANCELLED";
            default -> outcome.errorCode() == null ? "RUN_INTERRUPTED" : outcome.errorCode();
        };
        for (String toolCallId : List.copyOf(timings.keySet())) {
            ToolCallTiming timing = timings.remove(toolCallId);
            if (timing == null) {
                continue;
            }
            ToolInvocationEvidence evidence = evidenceByCall.remove(evidenceKey(toolCallId));
            Map<String, Object> payload = new LinkedHashMap<>();
            put(payload, "toolAlias", timing.alias());
            put(payload, "toolCallId", toolCallId);
            put(payload, "errorCode", errorCode);
            payload.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - timing.startedNanos()));
            if (evidence != null && evidence.hasArguments()) {
                payload.put("arguments", evidence.argumentsEvidence());
            }
            try {
                execution.publisher().emit("tool.failed", payload);
            } catch (RuntimeException exception) {
                log.warn(
                        "failed to close unfinished tool audit: run={} toolCallId={} errorCode={}",
                        execution.runId(),
                        toolCallId,
                        errorCode);
            }
        }
    }

    private String resolveModelSecret(RunExecution execution) {
        AgentManifest.ModelSpec model = execution.release().manifest().model();
        if (model.credentialRef() == null || model.credentialRef().isBlank()) {
            return "";
        }
        CredentialResolveResponse credential = control.resolveCredential(new CredentialResolveRequest(
                execution.runId(),
                execution.executionToken(),
                model.credentialRef(),
                model.secretRevision(),
                "RUN",
                null));
        if (credential == null
                || credential.secret() == null
                || credential.secret().isBlank()) {
            throw new ControlException(503, ErrorCodes.CREDENTIAL_UNAVAILABLE, "模型凭据暂不可用");
        }
        return credential.secret();
    }

    private ScheduledFuture<?> startHeartbeat(RunExecution execution) {
        AtomicLong lastConfirmed = new AtomicLong(System.nanoTime());
        long interval = properties.run().heartbeatIntervalMs();
        long lossTimeout = properties.run().ownershipLossTimeoutMs();
        return heartbeatScheduler.scheduleWithFixedDelay(
                () -> {
                    if (execution.terminal() || execution.guard().cancelled()) {
                        return;
                    }
                    try {
                        RunClaimResponse response = control.heartbeat(new RunClaimRequest(
                                execution.runId(),
                                execution.executionToken(),
                                execution.instanceId(),
                                UUID.randomUUID().toString()));
                        if (response == null) {
                            heartbeatFailed(execution, lastConfirmed, lossTimeout, ErrorCodes.EXECUTION_LOST, "心跳无响应");
                            return;
                        }
                        if (!response.allowed()) {
                            cancelLocal(execution, claimReasonCode(response.reason()), "Control 已撤销执行权");
                            return;
                        }
                        if (response.cancelRequired()) {
                            cancellationWarning(execution, "Control 要求停止 Run");
                            cancelLocal(execution, "CANCELLED", "Control 要求取消 Run");
                            return;
                        }
                        lastConfirmed.set(System.nanoTime());
                    } catch (ControlException exception) {
                        heartbeatFailed(
                                execution,
                                lastConfirmed,
                                lossTimeout,
                                ErrorCodes.EXECUTION_LOST,
                                "心跳失败：" + exception.code());
                    } catch (RuntimeException exception) {
                        heartbeatFailed(execution, lastConfirmed, lossTimeout, ErrorCodes.EXECUTION_LOST, "心跳异常");
                    }
                },
                interval,
                interval,
                TimeUnit.MILLISECONDS);
    }

    private void heartbeatFailed(
            RunExecution execution, AtomicLong lastConfirmed, long lossTimeoutMs, String code, String message) {
        long silentMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastConfirmed.get());
        if (silentMillis >= lossTimeoutMs) {
            log.warn("run ownership lost: run={} silentMs={}", execution.runId(), silentMillis);
            cancellationWarning(execution, "超过 10 秒无法确认执行权，已停止本地执行");
            cancelLocal(execution, code, message);
        }
    }

    private void cancellationWarning(RunExecution execution, String message) {
        execution.warning(message);
        try {
            RunEventPublisher publisher = execution.publisher();
            if (publisher != null) {
                publisher.emit("run.warning", Map.of("code", "EXECUTION_LOST", "message", message));
            }
        } catch (RuntimeException ignored) {
            // 事件通道已失败时仍必须继续终止本地模型流
        }
    }

    private void cancelLocal(RunExecution execution, String code, String message) {
        io.opentelemetry.api.trace.Span.current()
                .setAttribute("ai.run.id", execution.runId().toString());
        io.opentelemetry.api.trace.Span.current().setAttribute("ai.agent.release", String.valueOf(execution.release()));
        RunGuard guard = execution.guard();
        if (guard != null) {
            guard.cancel(code, message);
        }
        Future<?> future = execution.future();
        if (future != null && future.cancel(false) && execution.tryCancelQueued()) {
            // 只有任务尚未离开队列时才可由取消线程提交唯一终态；已运行任务由 worker finally 收敛。
            long durationNanos = TimeUnit.MILLISECONDS.toNanos(
                    Duration.between(execution.acceptedAt(), Instant.now()).toMillis());
            finish(execution, cancellationOutcome(code, message), "", Math.max(0L, durationNanos), true);
            return;
        }
        Disposable subscription = execution.subscription();
        if (subscription != null) {
            subscription.dispose();
        }
    }

    private ExecutionOutcome outcome(RunExecution execution, Throwable failure, boolean timedOut, String answer) {
        io.opentelemetry.api.trace.Span.current()
                .setAttribute("ai.run.id", execution.runId().toString());
        io.opentelemetry.api.trace.Span.current().setAttribute("ai.agent.release", String.valueOf(execution.release()));
        RunGuard guard = execution.guard();
        if (guard != null && guard.cancelled()) {
            return cancellationOutcome(guard.cancelCode(), guard.cancelReason());
        }
        if (timedOut || isTimeout(failure)) {
            return new ExecutionOutcome(RunTerminalStatus.TIMED_OUT, "RUN_TIMEOUT", "Run 超过执行截止时间");
        }
        if (failure instanceof RunGuard.RunAbortedException aborted) {
            if ("TIMED_OUT".equals(aborted.code())) {
                return new ExecutionOutcome(RunTerminalStatus.TIMED_OUT, "RUN_TIMEOUT", aborted.getMessage());
            }
            return new ExecutionOutcome(RunTerminalStatus.FAILED, aborted.code(), aborted.getMessage());
        }
        if (failure instanceof ControlException controlException) {
            return new ExecutionOutcome(
                    RunTerminalStatus.FAILED, controlException.code(), controlException.getMessage());
        }
        if (failure != null) {
            var quotaFailure = ModelQuotaFailure.from(failure);
            if (quotaFailure.isPresent()) {
                return new ExecutionOutcome(
                        RunTerminalStatus.FAILED,
                        quotaFailure.get().code(),
                        quotaFailure.get().message());
            }
            return new ExecutionOutcome(RunTerminalStatus.FAILED, "RUN_FAILED", "Agent 执行失败");
        }
        if (answer == null || answer.isBlank()) {
            return new ExecutionOutcome(RunTerminalStatus.FAILED, "EMPTY_RESULT", "Agent 未返回可展示的结论");
        }
        return new ExecutionOutcome(RunTerminalStatus.SUCCEEDED, null, null);
    }

    private static ExecutionOutcome cancellationOutcome(String code, String message) {
        if ("CANCELLED".equals(code) || code == null) {
            return new ExecutionOutcome(RunTerminalStatus.CANCELLED, null, null);
        }
        if ("TIMED_OUT".equals(code) || "RUN_TIMEOUT".equals(code)) {
            return new ExecutionOutcome(RunTerminalStatus.TIMED_OUT, "RUN_TIMEOUT", message);
        }
        if (ErrorCodes.EXECUTION_LOST.equals(code)) {
            return new ExecutionOutcome(RunTerminalStatus.FAILED, code, message);
        }
        return new ExecutionOutcome(RunTerminalStatus.FAILED, code, message);
    }

    private void finish(RunExecution execution, ExecutionOutcome outcome, String answer, long startedNanos) {
        finish(execution, outcome, answer, startedNanos, false);
    }

    private void finish(
            RunExecution execution,
            ExecutionOutcome outcome,
            String answer,
            long startedNanos,
            boolean terminalAlreadyClaimed) {
        if (!terminalAlreadyClaimed && !execution.markTerminal()) {
            return;
        }
        ScheduledFuture<?> heartbeat = execution.heartbeat();
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        execution.status(outcome.status().name());
        execution.finishedAt(Instant.now());
        if (outcome.errorCode() != null) {
            execution.error(outcome.errorCode(), outcome.errorMessage());
        }
        Disposable subscription = execution.subscription();
        if (subscription != null) {
            subscription.dispose();
        }
        ReActAgent agent = execution.agent();
        if (agent != null) {
            try {
                agent.close();
            } catch (RuntimeException exception) {
                log.debug("agent close failed: run={}", execution.runId());
            }
        }
        long durationNanos = System.nanoTime() - startedNanos;
        long durationMs = TimeUnit.NANOSECONDS.toMillis(durationNanos);
        try {
            // 终态事件由 Control 在 :complete 事务中统一生成（TIMED_OUT 也映射为 run.failed），
            // Runtime 只负责把已产生的增量事件刷完，避免同一 Run 出现重复终态事件。
            RunEventPublisher publisher = execution.publisher();
            if (publisher != null) {
                publisher.flushAndAwait(Duration.ofSeconds(5));
                publisher.close();
            }
        } catch (RuntimeException exception) {
            log.warn("event flush failed before completion: run={} code={}", execution.runId(), outcome.errorCode());
        }

        String finalAnswer = limitAnswer(answer);
        if (finalAnswer.length() < (answer == null ? 0 : answer.length())) {
            execution.warning("最终回答超过 60000 字符，已截断");
        }
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputTokens", execution.inputTokens());
        usage.put("outputTokens", execution.outputTokens());
        usage.put("totalTokens", execution.inputTokens() + execution.outputTokens());
        usage.put("modelCalls", execution.modelCalls());
        usage.put("toolCalls", execution.guard() == null ? 0 : execution.guard().toolCalls());
        usage.put("durationMs", durationMs);
        RunCompletionRequest completion = new RunCompletionRequest(
                execution.runId(),
                execution.executionToken(),
                execution.instanceId(),
                UUID.randomUUID().toString(),
                outcome.status(),
                finalAnswer,
                List.of(),
                execution.warnings(),
                usage,
                outcome.errorCode(),
                outcome.errorMessage());
        submitCompletion(completion);
        metrics.counter("run_terminal_total", "status", outcome.status().name()).increment();
        Timer.builder("run_duration")
                .tag("status", outcome.status().name())
                .register(metrics)
                .record(durationNanos, TimeUnit.NANOSECONDS);
        activeRuns.decrementAndGet();
        admission.leave();
    }

    private void submitCompletion(RunCompletionRequest request) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                control.complete(request);
                return;
            } catch (ControlException exception) {
                if (exception.status() >= 400 && exception.status() < 500) {
                    log.warn(
                            "run completion rejected: run={} code={} status={}",
                            request.runId(),
                            exception.code(),
                            exception.status());
                    return;
                }
                metrics.counter("run_completion_failures_total", "code", exception.code())
                        .increment();
            } catch (RuntimeException exception) {
                metrics.counter("run_completion_failures_total", "code", "COMPLETION_FAILED")
                        .increment();
            }
            sleep(200L * attempt);
        }
        log.error("run completion gave up: run={}", request.runId());
    }

    private Map<String, Object> startedPayload(RunExecution execution) {
        AgentManifest manifest = execution.release().manifest();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", execution.runId().toString());
        payload.put("releaseId", execution.release().releaseId().toString());
        payload.put("manifestDigest", execution.release().manifestDigest());
        payload.put("agentKey", manifest.agentKey());
        payload.put("agentVersion", manifest.version());
        if (execution.dispatch().conversationId() != null) {
            payload.put("conversationId", execution.dispatch().conversationId().toString());
        }
        payload.put("instanceId", execution.instanceId());
        return payload;
    }

    private static String actor(RunExecution execution) {
        if (execution.claim() != null && execution.claim().actorId() != null) {
            return execution.claim().actorId();
        }
        return execution.dispatch().actorId();
    }

    private static String conversation(RunExecution execution) {
        if (execution.claim() != null && execution.claim().conversationId() != null) {
            return execution.claim().conversationId().toString();
        }
        return execution.dispatch().conversationId() == null
                ? execution.runId().toString()
                : execution.dispatch().conversationId().toString();
    }

    private static int effectiveIterations(PreparedRelease release, RunClaimResponse claim) {
        int value = release.manifest().budget().maxIterations();
        if (claim.budget() != null && claim.budget().maxIterations() > 0) {
            value = Math.min(value, claim.budget().maxIterations());
        }
        return Math.max(1, value);
    }

    private static int effectiveToolCalls(PreparedRelease release, RunClaimResponse claim) {
        int value = release.manifest().budget().maxToolCalls();
        if (claim.budget() != null && claim.budget().maxToolCalls() > 0) {
            value = Math.min(value, claim.budget().maxToolCalls());
        }
        return Math.max(1, value);
    }

    private Instant effectiveDeadline(RunDispatchRequest request, RunClaimResponse claim, PreparedRelease release) {
        Instant now = Instant.now();
        long timeout = Math.min(
                properties.run().maxRunTimeoutMs(), release.manifest().budget().runTimeoutMs());
        Instant deadline = now.plusMillis(Math.max(1L, timeout));
        if (request.deadline() != null && request.deadline().isBefore(deadline)) {
            deadline = request.deadline();
        }
        if (claim.deadline() != null && claim.deadline().isBefore(deadline)) {
            deadline = claim.deadline();
        }
        return deadline;
    }

    private void verifyRelease(String agentKey, String version, RunDispatchRequest request, PreparedRelease release) {
        if (!release.manifestDigest().equalsIgnoreCase(request.manifestDigest())) {
            throw new RunRejectedException(409, ErrorCodes.REVISION_CONFLICT, "manifestDigest 与已准备版本不一致", null);
        }
        if (!release.manifest().agentKey().equals(agentKey)
                || !release.manifest().version().equals(version)
                || !agentKey.equals(request.agentKey())
                || !version.equals(request.agentVersion())) {
            throw new RunRejectedException(409, ErrorCodes.REVISION_CONFLICT, "Agent 版本定位不一致", null);
        }
    }

    private static void validateDispatch(String agentKey, String version, RunDispatchRequest request) {
        if (blank(agentKey) || blank(version) || request == null) {
            throw new RunRejectedException(400, "BAD_REQUEST", "Agent 版本与请求体不能为空", null);
        }
        if (request.runId() == null
                || request.releaseId() == null
                || request.executionToken() <= 0
                || blank(request.manifestDigest())
                || blank(request.agentKey())
                || blank(request.agentVersion())) {
            throw new RunRejectedException(400, "BAD_REQUEST", "Run 派发字段不完整", null);
        }
    }

    private static String claimReasonCode(String reason) {
        if (reason != null && reason.toUpperCase(java.util.Locale.ROOT).contains("STALE")) {
            return ErrorCodes.STALE_EXECUTION_TOKEN;
        }
        if (reason != null && reason.toUpperCase(java.util.Locale.ROOT).contains("DEADLINE")) {
            return "RUN_TIMEOUT";
        }
        return ErrorCodes.EXECUTION_LOST;
    }

    private static String errorCode(ToolResultState state) {
        if (state == ToolResultState.DENIED) {
            return ErrorCodes.TOOL_DENIED;
        }
        if (state == ToolResultState.INTERRUPTED) {
            return "CANCELLED";
        }
        return ErrorCodes.MCP_TOOL_ERROR;
    }

    private static String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : String.valueOf(value);
    }

    static String auditErrorCode(ToolResultEndEvent event, ToolResultState state) {
        String auditErrorCode = metadataString(event.getMetadata(), ToolAuditMetadata.ERROR_CODE);
        return auditErrorCode == null ? errorCode(state) : auditErrorCode;
    }

    static String auditDecision(ToolResultEndEvent event, ToolResultState state) {
        String decision = metadataString(event.getMetadata(), ToolAuditMetadata.DECISION);
        if (decision != null) {
            return decision;
        }
        return state == ToolResultState.DENIED ? ToolAuditMetadata.DENIED : ToolAuditMetadata.PENDING;
    }

    private static boolean isTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof java.util.concurrent.TimeoutException
                    || current.getClass().getName().endsWith("TimeoutException")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void closePublisher(RunExecution execution) {
        RunEventPublisher publisher = execution.publisher();
        if (publisher != null) {
            publisher.close();
        }
    }

    private static String limitAnswer(String answer) {
        if (answer == null) {
            return "";
        }
        if (answer.length() <= MAX_FINAL_ANSWER_CHARS) {
            return answer;
        }
        return answer.substring(0, MAX_FINAL_ANSWER_CHARS) + "\n\n[回答已截断]";
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
