package top.yealike.ipe.ai.run;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.ipe.ai.run.RunRecords.RunEvent;

/** 事件发布：游标分配与事件落库在同一事务，先落库再广播，保证断线重放不丢事件。 */
@Service
public class RunEventService {

    private final RunRepository runs;
    private final ConversationChannels channels;

    public RunEventService(RunRepository runs, ConversationChannels channels) {
        this.runs = runs;
        this.channels = channels;
    }

    @Transactional
    public RunEvent publish(UUID conversationId, UUID runId, String type, Map<String, Object> payload) {
        long cursor = runs.nextEventCursor(conversationId);
        Instant now = Instant.now();
        runs.insertEvent(conversationId, runId, cursor, type, payload, now);
        RunEvent event = new RunEvent(cursor, conversationId, runId, type, payload, now);
        // 外层事务可能随后回滚；只有提交后才允许客户端看到事件。
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        channels.publish(event);
                    }
                });
        return event;
    }

    public List<RunEvent> replay(UUID conversationId, long afterCursor, int limit) {
        return runs.eventsAfter(conversationId, afterCursor, limit);
    }

    public long latestCursor(UUID conversationId) {
        return runs.latestCursor(conversationId);
    }
}
