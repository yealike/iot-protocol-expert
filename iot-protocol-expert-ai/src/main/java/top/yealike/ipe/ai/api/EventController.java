package top.yealike.ipe.ai.api;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import top.yealike.ipe.ai.conversation.ConversationService;
import top.yealike.ipe.ai.identity.CurrentUser;
import top.yealike.ipe.ai.identity.IdentityService;
import top.yealike.ipe.ai.run.RunEventService;

/** 按持久化游标读取已提交事件，重放和实时事件采用相同顺序，支持跨实例。 */
@RestController
@RequestMapping("/api/ai")
public class EventController {
    private static final int BATCH = 200;
    private final RunEventService events;
    private final ConversationService conversations;
    private final IdentityService identities;
    private final ScheduledExecutorService workers = Executors.newScheduledThreadPool(4, task -> {
        Thread thread = new Thread(task, "ai-sse-poll");
        thread.setDaemon(true);
        return thread;
    });

    public EventController(RunEventService events, ConversationService conversations, IdentityService identities) {
        this.events = events;
        this.conversations = conversations;
        this.identities = identities;
    }

    @GetMapping(value = "/conversations/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") long after,
            jakarta.servlet.http.HttpServletRequest request) {
        String cookie = request.getHeader("Cookie");
        var identity = CurrentUser.require();
        conversations.require(identity, id);
        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();
        Runnable cleanup = () -> {
            ScheduledFuture<?> scheduled = future.get();
            if (scheduled != null) scheduled.cancel(false);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        long[] cursor = {Math.max(0, after)};
        int[] ticks = {0};
        boolean[] ready = {false};
        future.set(workers.scheduleWithFixedDelay(
                () -> {
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
                        if (ticks[0] % 15 == 0) emitter.send(SseEmitter.event().comment("ping"));
                    } catch (Exception exception) {
                        cleanup.run();
                        emitter.completeWithError(exception);
                    }
                },
                100,
                1000,
                TimeUnit.MILLISECONDS));
        return emitter;
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }
}
