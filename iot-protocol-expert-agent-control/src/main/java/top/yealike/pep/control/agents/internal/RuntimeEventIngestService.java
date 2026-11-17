package top.yealike.pep.control.agents.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.yealike.pep.control.runs.ConversationChannels;
import top.yealike.pep.control.runs.RunRecords;
import top.yealike.pep.control.runs.RunRepository;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.control.support.JsonSupport;
import top.yealike.pep.runtime.contract.ErrorCodes;
import top.yealike.pep.runtime.contract.RunEvent;
import top.yealike.pep.runtime.contract.RunEventAck;
import top.yealike.pep.runtime.contract.RunEventBatch;

/** Runtime 事件入库：按 (runId,token,sourceSeq) 与 eventId 去重，事务提交后广播 SSE。 */
@Service
public class RuntimeEventIngestService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeEventIngestService.class);
    private static final int MAX_EVENTS = 32;
    private static final int MAX_EVENT_BYTES = 64 * 1024;

    private final RunRepository runs;
    private final ConversationChannels channels;
    private final TransactionTemplate transactions;

    public RuntimeEventIngestService(
            RunRepository runs, ConversationChannels channels, PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.channels = channels;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public RunEventAck ingest(UUID conversationId, RunEventBatch batch) {
        if (batch.events().isEmpty()) {
            return new RunEventAck(List.of(), 0);
        }
        if (batch.events().size() > MAX_EVENTS) {
            throw AiExceptions.badRequest("单批事件不能超过 " + MAX_EVENTS + " 条");
        }
        Set<Long> seqs = new HashSet<>();
        for (RunEvent event : batch.events()) {
            if (event.sourceSeq() <= 0 || !seqs.add(event.sourceSeq())) {
                throw AiExceptions.badRequest("sourceSeq 必须为单 Run 内唯一正整数");
            }
            if (event.type() == null || !event.type().matches("[a-z][a-z0-9_.-]{2,47}")) {
                throw AiExceptions.badRequest("事件类型非法");
            }
            if (JsonSupport.write(event.payload()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    > MAX_EVENT_BYTES) {
                throw AiExceptions.badRequest("单条事件不能超过 64 KiB");
            }
        }
        List<RunRecords.RunEvent> acceptedEvents = new ArrayList<>();
        List<Long> acceptedSeq = new ArrayList<>();
        transactions.executeWithoutResult(status -> {
            if (!runs.lockRuntimeEventRun(batch.runId(), batch.executionToken(), batch.instanceId())) {
                throw AiExceptions.conflict(ErrorCodes.STALE_EXECUTION_TOKEN, "Run 执行权已失效或已终结");
            }
            rejectContentConflicts(batch);
            int count = batch.events().size();
            long lastCursor = runs.reserveEventCursors(conversationId, count);
            long firstCursor = lastCursor - count + 1;
            Map<Long, RunRecords.RuntimeEventInsert> inserts = new LinkedHashMap<>();
            Map<Long, RunEvent> sourceEvents = new LinkedHashMap<>();
            for (int index = 0; index < count; index++) {
                RunEvent event = batch.events().get(index);
                UUID eventId = event.eventId() == null ? UUID.randomUUID() : event.eventId();
                long cursor = firstCursor + index;
                RunRecords.RuntimeEventInsert insert = new RunRecords.RuntimeEventInsert(
                        conversationId,
                        batch.runId(),
                        cursor,
                        event.type(),
                        JsonSupport.write(event.payload()),
                        event.createdAt() == null ? Instant.now() : event.createdAt(),
                        batch.executionToken(),
                        event.sourceSeq(),
                        eventId);
                inserts.put(event.sourceSeq(), insert);
                sourceEvents.put(event.sourceSeq(), event);
            }
            Set<Long> inserted = runs.insertRuntimeEvents(List.copyOf(inserts.values()));
            for (RunRecords.RuntimeEventInsert insert : inserts.values()) {
                RunEvent source = sourceEvents.get(insert.sourceSeq());
                acceptedSeq.add(insert.sourceSeq());
                if (!inserted.contains(insert.sourceSeq())) {
                    log.debug(
                            "runtime event already persisted: run={} sourceSeq={}", batch.runId(), insert.sourceSeq());
                    continue;
                }
                acceptedEvents.add(new RunRecords.RunEvent(
                        insert.cursor(),
                        insert.conversationId(),
                        insert.runId(),
                        source.type(),
                        source.payload(),
                        insert.createdAt()));
            }
            acceptedEvents.forEach(event -> recordToolCall(batch, event));
        });
        acceptedEvents.forEach(channels::publish);
        long lastCursor =
                acceptedEvents.isEmpty() ? 0 : acceptedEvents.getLast().cursor();
        return new RunEventAck(List.copyOf(acceptedSeq), lastCursor);
    }

    private void rejectContentConflicts(RunEventBatch batch) {
        Map<Long, RunEvent> incoming = new LinkedHashMap<>();
        for (RunEvent event : batch.events()) {
            incoming.put(event.sourceSeq(), event);
        }
        for (RunRecords.RuntimeEventFingerprint existing :
                runs.runtimeEventFingerprints(batch.runId(), List.copyOf(incoming.keySet()))) {
            RunEvent event = incoming.get(existing.sourceSeq());
            if (event == null) {
                continue;
            }
            boolean sameType = event.type().equals(existing.type());
            boolean samePayload = JsonSupport.mapper()
                    .readTree(JsonSupport.write(event.payload()))
                    .equals(JsonSupport.mapper().readTree(JsonSupport.write(existing.payload())));
            if (!sameType || !samePayload) {
                throw AiExceptions.conflict(
                        ErrorCodes.EVENT_CONFLICT, "sourceSeq " + existing.sourceSeq() + " 已持久化为不同内容，拒绝覆盖");
            }
        }
    }

    private void recordToolCall(RunEventBatch batch, RunRecords.RunEvent event) {
        Object rawToolCallId = event.payload().get("toolCallId");
        if (rawToolCallId == null || String.valueOf(rawToolCallId).isBlank()) {
            return;
        }
        String toolCallId = String.valueOf(rawToolCallId);
        UUID id = UUID.nameUUIDFromBytes((batch.runId() + ":" + toolCallId).getBytes(StandardCharsets.UTF_8));
        switch (event.type()) {
            case "tool.started" ->
                runs.insertRuntimeToolCall(
                        id,
                        batch.runId(),
                        event.conversationId(),
                        string(event.payload().get("toolAlias")),
                        string(event.payload().get("componentKey")),
                        string(event.payload().get("componentVersion")),
                        string(event.payload().get("originalName")),
                        toolCallId,
                        batch.executionToken(),
                        event.createdAt());
            case "tool.completed", "tool.failed" ->
                runs.finishRuntimeToolCall(
                        id,
                        batch.runId(),
                        "tool.completed".equals(event.type()) ? "SUCCEEDED" : "FAILED",
                        event.payload(),
                        integer(event.payload().get("durationMs")),
                        event.createdAt());
            default -> {
                // 非工具事件无需更新审计表
            }
        }
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
