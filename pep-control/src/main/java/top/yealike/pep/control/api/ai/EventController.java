package top.yealike.pep.control.api.ai;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import top.yealike.pep.control.access.CurrentUser;
import top.yealike.pep.control.access.IdentityService;
import top.yealike.pep.control.conversations.ConversationService;
import top.yealike.pep.control.observability.AiObservability;
import top.yealike.pep.control.runs.RunEventService;

/** 按持久化游标读取已提交事件，重放和实时事件采用相同顺序，支持跨实例。 */
@RestController
@RequestMapping("/api/ai/v1")
@Tag(name = "AI 对话事件", description = "SSE 事件流，支持断点续传")
public class EventController {
    private static final int BATCH = 200;
    private final RunEventService events;
    private final ConversationService conversations;
    private final IdentityService identities;
    private final AiObservability observability;
    private final Semaphore streams = new Semaphore(256);
    private final ScheduledThreadPoolExecutor workers = createWorkers();

    private static ScheduledThreadPoolExecutor createWorkers() {
        var executor = new ScheduledThreadPoolExecutor(
                4, Thread.ofPlatform().daemon(true).name("ai-sse-poll-", 0).factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    public EventController(
            RunEventService events,
            ConversationService conversations,
            IdentityService identities,
            AiObservability observability) {
        this.events = events;
        this.conversations = conversations;
        this.identities = identities;
        this.observability = observability;
    }

    @GetMapping(value = "/conversations/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅对话事件流", description = "实时推送消息增量、工具进度和 Run 终态；使用游标续传")
    public SseEmitter stream(
            @Parameter(description = "会话 ID") @PathVariable UUID id,
            @Parameter(description = "续传游标的兼容参数", example = "0") @RequestParam(defaultValue = "0") long after,
            @Parameter(description = "上次收到事件的游标；优先于 after", example = "42") @RequestParam(required = false)
                    Long afterCursor,
            jakarta.servlet.http.HttpServletRequest request) {
        var identity = CurrentUser.require();
        conversations.require(identity, id);
        if (!streams.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many event streams");
        }
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(30));
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();
        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) streams.release();
            ScheduledFuture<?> scheduled = future.get();
            if (scheduled != null) scheduled.cancel(false);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        long[] cursor = {Math.max(0, afterCursor == null ? after : afterCursor)};
        int[] ticks = {0};
        boolean[] ready = {false};
        try {
            future.set(workers.scheduleWithFixedDelay(
                    observability.wrap(() -> {
                        try {
                            ++ticks[0];
                            var current = identities
                                    .userIdentity(identity.username())
                                    .orElseThrow(() -> new IllegalStateException("User authorization changed"));
                            if (current.authVersion() != identity.authVersion())
                                throw new IllegalStateException("User authorization changed");
                            conversations.require(current, id);
                            var batch = events.replay(id, cursor[0], BATCH);
                            for (var event : batch) {
                                emitter.send(SseEmitter.event()
                                        .id(String.valueOf(event.cursor()))
                                        .name(event.type())
                                        .data(event.payload(), MediaType.APPLICATION_JSON));
                                cursor[0] = event.cursor();
                            }
                            if (!ready[0] && batch.size() < BATCH) {
                                emitter.send(SseEmitter.event()
                                        .name("stream.ready")
                                        .data(java.util.Map.of("cursor", cursor[0])));
                                ready[0] = true;
                            }
                            if (ticks[0] % 15 == 0)
                                emitter.send(SseEmitter.event().comment("ping"));
                        } catch (Exception exception) {
                            cleanup.run();
                            emitter.completeWithError(exception);
                        }
                    }),
                    100,
                    1000,
                    TimeUnit.MILLISECONDS));
            // 完成回调可能先于 future.set 执行，避免遗留轮询任务。
            if (closed.get()) future.get().cancel(false);
        } catch (RuntimeException exception) {
            cleanup.run();
            throw exception;
        }
        return emitter;
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }
}
