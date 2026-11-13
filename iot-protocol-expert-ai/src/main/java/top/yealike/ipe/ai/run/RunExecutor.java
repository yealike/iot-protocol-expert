package top.yealike.ipe.ai.run;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import top.yealike.ipe.ai.agent.AgentCatalog;
import top.yealike.ipe.ai.agent.AgentRunner;
import top.yealike.ipe.ai.config.AiProperties;
import top.yealike.ipe.ai.conversation.ConversationRecords.Citation;
import top.yealike.ipe.ai.conversation.ConversationRecords.Message;
import top.yealike.ipe.ai.conversation.ConversationRepository;
import top.yealike.ipe.ai.identity.UserIdentity;
import top.yealike.ipe.ai.policy.PolicyService;
import top.yealike.ipe.ai.run.RunRecords.Run;
import top.yealike.ipe.ai.support.AiExceptions;
import top.yealike.ipe.ai.tool.ToolSession;

/**
 * 单次 Run 的执行器。
 *
 * <p>在独立工作线程中消费 Agent 事件流：按批次持久化文本增量、记录工具进度，并在安全点响应暂停/取消。 不向用户暴露模型隐藏推理；只展示执行进度、已授权工具名与最终结果。
 */
@Service
public class RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);

    private static final Set<AgentEventType> SAFE_POINTS = Set.of(
            AgentEventType.TEXT_BLOCK_END,
            AgentEventType.TOOL_RESULT_END,
            AgentEventType.MODEL_CALL_END,
            AgentEventType.AGENT_END);

    private final AgentCatalog catalog;
    private final AgentRunner agents;
    private final ConversationRepository conversations;
    private final RunRepository runs;
    private final RunEventService events;
    private final PolicyService policy;
    private final AiProperties properties;
    private final FallbackAnswerService fallback;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public RunExecutor(
            AgentCatalog catalog,
            AgentRunner agents,
            ConversationRepository conversations,
            RunRepository runs,
            RunEventService events,
            PolicyService policy,
            AiProperties properties,
            FallbackAnswerService fallback,
            io.micrometer.core.instrument.MeterRegistry metrics,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.catalog = catalog;
        this.agents = agents;
        this.conversations = conversations;
        this.runs = runs;
        this.events = events;
        this.policy = policy;
        this.properties = properties;
        this.fallback = fallback;
        this.metrics = metrics;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    private void countFinished(String status) {
        metrics.counter("ai_runs_finished_total", "status", status).increment();
    }

    public void execute(Run run, UserIdentity identity) {
        UUID conversationId = run.conversationId();
        events.publish(
                conversationId,
                run.id(),
                "run.started",
                Map.of("runId", run.id().toString(), "agentKey", run.agentKey(), "status", run.status()));
        Instant startedAt = Instant.now();
        ToolSession session = new ToolSession(
                identity,
                conversationId,
                run.id(),
                run.agentKey(),
                policy.version(),
                policy.stationScopeHeader(identity),
                properties.run().maxToolCalls());

        try {
            AgentCatalog.Definition definition = agents.requireDefinition(run.agentKey());
            List<Msg> messages = contextMessages(run);
            if (!properties.model().usable()) {
                completeWithFallback(run, session, definition);
                return;
            }
            streamAgent(run, session, definition, messages, startedAt);
        } catch (AiExceptions.AiException exception) {
            failOrCancel(run, session, exception.code(), exception.getMessage(), startedAt);
        } catch (RuntimeException exception) {
            log.error("run execution failed: run={}", run.id(), exception);
            failOrCancel(run, session, "RUN_FAILED", "执行失败，请稍后重试或联系管理员", startedAt);
        }
    }

    private void streamAgent(
            Run run, ToolSession session, AgentCatalog.Definition definition, List<Msg> messages, Instant startedAt) {
        BlockingQueue<AgentEvent> queue = new LinkedBlockingQueue<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean(false);

        Flux<AgentEvent> flux = agents.stream(definition, messages, session);
        Disposable subscription = flux.subscribe(
                queue::offer,
                failure -> {
                    error.set(failure);
                    finished.set(true);
                },
                () -> finished.set(true));

        StringBuilder pending = new StringBuilder();
        StringBuilder full = new StringBuilder();
        AtomicReference<String> finalText = new AtomicReference<>();
        AgentEventType lastType = null;
        long lastFlush = System.nanoTime();
        long lastControlCheck = System.nanoTime();
        Instant deadline = startedAt.plusMillis(properties.run().timeoutMs());
        boolean paused = false;
        boolean cancelled = false;

        try {
            while (true) {
                AgentEvent event = queue.poll(220, TimeUnit.MILLISECONDS);
                if (event != null) {
                    lastType = event.getType();
                    handleEvent(run, session, event, pending, full, finalText);
                }

                long now = System.nanoTime();
                if (pending.length() >= 400 || now - lastFlush > 300_000_000L) {
                    flushDelta(run, pending);
                    lastFlush = now;
                }

                // 取消优先于暂停：DDL 保证 PAUSE_REQUESTED 不回退为 CANCELLED 时按 PAUSED 处理
                if (now - lastControlCheck > 700_000_000L) {
                    lastControlCheck = now;
                    if (runs.isCancellationRequested(run.id())) {
                        cancelled = true;
                        subscription.dispose();
                        break;
                    }
                    if (runs.isPauseRequested(run.id()) && isSafePoint(lastType)) {
                        paused = true;
                        subscription.dispose();
                        break;
                    }
                }

                if (Instant.now().isAfter(deadline)) {
                    subscription.dispose();
                    throw new AiExceptions.AiException(504, "RUN_TIMEOUT", "本次执行超时，已保留已完成的部分");
                }
                if (finished.get() && queue.isEmpty()) {
                    break;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            subscription.dispose();
            throw new IllegalStateException("run interrupted", exception);
        } finally {
            subscription.dispose();
        }

        flushDelta(run, pending);

        if (cancelled) {
            cancel(run, session, startedAt);
            return;
        }
        if (paused) {
            Map<String, Object> usage = usage(session, startedAt);
            if (!runs.markPaused(run.id(), usage)) {
                if (runs.isCancellationRequested(run.id())) cancel(run, session, startedAt);
                return;
            }
            countFinished("PAUSED");
            events.publish(
                    run.conversationId(),
                    run.id(),
                    "run.paused",
                    Map.of("status", RunRecords.PAUSED, "citations", citations(session)));
            return;
        }
        if (error.get() != null) {
            log.warn("agent stream failed: run={}", run.id(), error.get());
            failOrCancel(run, session, "MODEL_ERROR", "模型调用失败，请稍后重试", startedAt);
            return;
        }

        String answer = finalText.get();
        if (answer == null || answer.isBlank()) {
            answer = full.toString();
        }
        if (answer.isBlank()) {
            answer = "未生成可展示的答复。请补充订单号、协议编码或命令字后重试。";
            session.addWarning("模型未返回文本结果");
        }
        complete(run, session, answer, startedAt);
    }

    private void handleEvent(
            Run run,
            ToolSession session,
            AgentEvent event,
            StringBuilder pending,
            StringBuilder full,
            AtomicReference<String> finalText) {
        AgentEventType type = event.getType();
        if (type == AgentEventType.TEXT_BLOCK_DELTA && event instanceof TextBlockDeltaEvent delta) {
            String text = delta.getDelta();
            if (text != null && !text.isEmpty()) {
                pending.append(text);
                full.append(text);
            }
            return;
        }
        if (type == AgentEventType.TOOL_CALL_START && event instanceof ToolCallStartEvent tool) {
            events.publish(
                    run.conversationId(),
                    run.id(),
                    "tool.started",
                    Map.of("tool", safe(tool.getToolCallName()), "callId", safe(tool.getToolCallId())));
            return;
        }
        if (type == AgentEventType.TOOL_RESULT_END && event instanceof ToolResultEndEvent tool) {
            events.publish(
                    run.conversationId(),
                    run.id(),
                    "tool.finished",
                    Map.of(
                            "tool",
                            safe(tool.getToolCallName()),
                            "callId",
                            safe(tool.getToolCallId()),
                            "state",
                            String.valueOf(tool.getState())));
            return;
        }
        if (type == AgentEventType.AGENT_RESULT && event instanceof AgentResultEvent result) {
            String text = result.getResult() == null ? null : result.getResult().getTextContent();
            if (text != null && !text.isBlank()) {
                finalText.set(text);
            }
            return;
        }
        if (type == AgentEventType.EXCEED_MAX_ITERS && event instanceof ExceedMaxItersEvent maxIters) {
            session.addWarning("已达到推理轮次上限（" + maxIters.getMaxIters() + "），以下是已完成部分");
            events.publish(
                    run.conversationId(),
                    run.id(),
                    "run.warning",
                    Map.of("warning", "EXCEED_MAX_ITERS", "maxIters", maxIters.getMaxIters()));
            return;
        }
        if (type == AgentEventType.ALL_TOOLS_DENIED) {
            session.addWarning("本次执行的所有工具调用均被拒绝，请检查账号权限");
            events.publish(run.conversationId(), run.id(), "run.warning", Map.of("warning", "ALL_TOOLS_DENIED"));
        }
    }

    private void completeWithFallback(Run run, ToolSession session, AgentCatalog.Definition definition) {
        events.publish(
                run.conversationId(),
                run.id(),
                "run.warning",
                Map.of("warning", "MODEL_UNAVAILABLE", "message", "模型未配置，本次仅返回确定性工具结果"));
        String answer = fallback.answer(run, session, definition);
        complete(run, session, answer, Instant.now());
    }

    private void complete(Run run, ToolSession session, String answer, Instant startedAt) {
        Map<String, Object> usage = usage(session, startedAt);
        Boolean completed = transactions.execute(status -> {
            long seq = conversations.nextMessageSeq(run.conversationId());
            if (!runs.markCompleted(run.id(), usage)) return false;
            UUID messageId = UUID.randomUUID();
            List<Citation> citations = session.citations();
            conversations.insertMessage(messageId, run.conversationId(), run.id(), "assistant", answer, citations, "FINAL", seq, Instant.now());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageId", messageId.toString());
            payload.put("seq", seq);
            payload.put("citations", citations);
            payload.put("warnings", session.warnings());
            payload.put("usage", usage);
            events.publish(run.conversationId(), run.id(), "run.completed", payload);
            return true;
        });
        if (Boolean.TRUE.equals(completed)) countFinished("COMPLETED");
        else if (runs.isCancellationRequested(run.id())) cancel(run, session, startedAt);
    }

    private void failOrCancel(Run run, ToolSession session, String code, String message, Instant startedAt) {
        if (runs.isCancellationRequested(run.id())) cancel(run, session, startedAt);
        else fail(run, session, code, message, startedAt);
    }

    private void cancel(Run run, ToolSession session, Instant startedAt) {
        runs.markCancelled(run.id(), usage(session, startedAt));
        countFinished("CANCELLED");
        events.publish(run.conversationId(), run.id(), "run.cancelled", Map.of("status", RunRecords.CANCELLED));
    }

    private void fail(Run run, ToolSession session, String code, String message, Instant startedAt) {
        Map<String, Object> usage = usage(session, startedAt);
        if (!runs.markFailed(run.id(), code, message, usage)) {
            if (runs.isCancellationRequested(run.id())) cancel(run, session, startedAt);
            return;
        }
        countFinished("FAILED");
        events.publish(
                run.conversationId(),
                run.id(),
                "run.failed",
                Map.of("errorCode", code, "message", message, "warnings", session.warnings()));
    }

    private Map<String, Object> usage(ToolSession session, Instant startedAt) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("toolCalls", session.callCount());
        usage.put("durationMs", Duration.between(startedAt, Instant.now()).toMillis());
        usage.put("warnings", session.warnings());
        return usage;
    }

    private List<Map<String, Object>> citations(ToolSession session) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Citation citation : session.citations()) {
            list.add(Map.of(
                    "documentId", citation.documentId() == null ? "" : citation.documentId(),
                    "title", safe(citation.title()),
                    "protocolVersion", safe(citation.protocolVersion()),
                    "sectionPath", safe(citation.sectionPath()),
                    "pageNo", citation.pageNo() == null ? "" : citation.pageNo()));
        }
        return list;
    }

    private void flushDelta(Run run, StringBuilder pending) {
        if (pending.isEmpty()) {
            return;
        }
        String delta = pending.toString();
        pending.setLength(0);
        events.publish(run.conversationId(), run.id(), "text.delta", Map.of("delta", delta));
    }

    private List<Msg> contextMessages(Run run) {
        List<Message> history =
                conversations.listMessagesThrough(run.conversationId(), run.userMessageId(), properties.run().maxContextMessages());
        List<Msg> messages = new ArrayList<>();
        for (Message message : history) {
            if ("user".equals(message.role())) {
                messages.add(new UserMessage(message.content()));
            } else if ("assistant".equals(message.role())) {
                messages.add(new AssistantMessage(message.content()));
            }
        }
        if (messages.isEmpty()) {
            messages.add(new UserMessage("请说明需要查询的订单号或协议内容。"));
        }
        return messages;
    }

    private static boolean isSafePoint(AgentEventType type) {
        return type == null || SAFE_POINTS.contains(type);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** 供调度器判断是否需要降级执行。 */
    public boolean modelAvailable() {
        return properties.model().usable();
    }
}
