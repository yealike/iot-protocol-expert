package top.yealike.ipe.ai.api;

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
import top.yealike.ipe.ai.conversation.ConversationService;
import top.yealike.ipe.ai.identity.CurrentUser;
import top.yealike.ipe.ai.identity.IdentityService;
import top.yealike.ipe.ai.observability.AiObservability;
import top.yealike.ipe.ai.run.RunEventService;

/** 按持久化游标读取已提交事件，重放和实时事件采用相同顺序，支持跨实例。 */
@RestController
@RequestMapping("/api/ai")
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
    public SseEmitter stream(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") long after,
            jakarta.servlet.http.HttpServletRequest request) {
        String cookie = request.getHeader("Cookie");
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
        long[] cursor = {Math.max(0, after)};
        int[] ticks = {0};
        boolean[] ready = {false};
        try {
            future.set(workers.scheduleWithFixedDelay(
                    observability.wrap(() -> {
                        try {
                            ++ticks[0];
                            var current = identities.resolve(cookie);
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
