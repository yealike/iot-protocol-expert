package top.yealike.ipe.ai.run;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import top.yealike.ipe.ai.run.RunRecords.RunEvent;

/**
 * 单实例内的会话事件广播。
 *
 * <p>持久事件以数据库为准：SSE 断线按 cursor 重放，因此这里丢一次推送不影响最终一致性； 多实例下的跨节点推送由数据库轮询兜底（见 docs/ai-assistant.md）。
 */
@Service
public class ConversationChannels {

    private final Map<UUID, Set<Consumer<RunEvent>>> listeners = new ConcurrentHashMap<>();

    public AutoCloseable register(UUID conversationId, Consumer<RunEvent> listener) {
        Set<Consumer<RunEvent>> set = listeners.computeIfAbsent(conversationId, key -> new CopyOnWriteArraySet<>());
        set.add(listener);
        return () -> {
            set.remove(listener);
            if (set.isEmpty()) {
                listeners.remove(conversationId, set);
            }
        };
    }

    public void publish(RunEvent event) {
        Set<Consumer<RunEvent>> set = listeners.get(event.conversationId());
        if (set == null || set.isEmpty()) {
            return;
        }
        for (Consumer<RunEvent> listener : set) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // 单个订阅者失败不能影响其他订阅者与主流程
            }
        }
    }

    public int listenerCount(UUID conversationId) {
        Set<Consumer<RunEvent>> set = listeners.get(conversationId);
        return set == null ? 0 : set.size();
    }
}
