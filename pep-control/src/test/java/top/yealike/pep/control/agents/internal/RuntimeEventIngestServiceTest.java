package top.yealike.pep.control.agents.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import top.yealike.pep.control.runs.ConversationChannels;
import top.yealike.pep.control.runs.RunRecords.RuntimeEventInsert;
import top.yealike.pep.control.runs.RunRepository;
import top.yealike.pep.runtime.contract.RunEvent;
import top.yealike.pep.runtime.contract.RunEventAck;
import top.yealike.pep.runtime.contract.RunEventBatch;

class RuntimeEventIngestServiceTest {

    @Test
    void reservesCursorOnceAndRecordsToolAudit() {
        RunRepository runs = mock(RunRepository.class);
        ConversationChannels channels = mock(ConversationChannels.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(status);

        UUID conversationId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-11-16T05:00:00Z");
        Instant finishedAt = Instant.parse("2026-11-16T05:00:01Z");
        when(runs.reserveEventCursors(conversationId, 2)).thenReturn(10L);
        when(runs.lockRuntimeEventRun(runId, 7L, "runtime-test")).thenReturn(true);
        when(runs.insertRuntimeEvents(anyList())).thenReturn(Set.of(1L, 2L));

        RunEvent started = new RunEvent(
                "tool.started",
                Map.of(
                        "toolAlias", "mcp_ops__get_device_detail",
                        "toolCallId", "call-1",
                        "componentKey", "ops",
                        "componentVersion", "1.0.0",
                        "originalName", "get_device_detail"),
                1L,
                UUID.randomUUID(),
                startedAt);
        RunEvent completed = new RunEvent(
                "tool.completed",
                Map.of("toolAlias", "mcp_ops__get_device_detail", "toolCallId", "call-1", "durationMs", 25),
                2L,
                UUID.randomUUID(),
                finishedAt);
        RunEventBatch batch = new RunEventBatch(runId, 7L, "runtime-test", "request-1", List.of(started, completed));

        RunEventAck ack = new RuntimeEventIngestService(runs, channels, transactions).ingest(conversationId, batch);

        assertThat(ack.acceptedSourceSeq()).containsExactly(1L, 2L);
        assertThat(ack.lastCursor()).isEqualTo(10L);
        ArgumentCaptor<List<RuntimeEventInsert>> inserts = ArgumentCaptor.forClass(List.class);
        verify(runs).insertRuntimeEvents(inserts.capture());
        assertThat(inserts.getValue()).extracting(RuntimeEventInsert::cursor).containsExactly(9L, 10L);
        verify(channels, times(2)).publish(any());
        verify(transactions).commit(status);

        UUID toolCallId = UUID.nameUUIDFromBytes((runId + ":call-1").getBytes(StandardCharsets.UTF_8));
        verify(runs)
                .insertRuntimeToolCall(
                        eq(toolCallId),
                        eq(runId),
                        eq(conversationId),
                        eq("mcp_ops__get_device_detail"),
                        eq("ops"),
                        eq("1.0.0"),
                        eq("get_device_detail"),
                        eq("call-1"),
                        eq(7L),
                        eq(startedAt));
        verify(runs)
                .finishRuntimeToolCall(eq(toolCallId), eq(runId), eq("SUCCEEDED"), anyMap(), eq(25), eq(finishedAt));
    }

    @Test
    void duplicateBatchStillAcknowledgesPersistedSourceSequences() {
        RunRepository runs = mock(RunRepository.class);
        ConversationChannels channels = mock(ConversationChannels.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(status);

        UUID conversationId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RunEvent event = new RunEvent("assistant.delta", Map.of("delta", "a"), 1L, UUID.randomUUID(), Instant.now());
        RunEventBatch batch = new RunEventBatch(runId, 7L, "runtime-test", "request-1", List.of(event));
        when(runs.reserveEventCursors(conversationId, 1)).thenReturn(8L);
        when(runs.lockRuntimeEventRun(runId, 7L, "runtime-test")).thenReturn(true);
        when(runs.insertRuntimeEvents(anyList())).thenReturn(Set.of());

        RunEventAck ack = new RuntimeEventIngestService(runs, channels, transactions).ingest(conversationId, batch);

        assertThat(ack.acceptedSourceSeq()).containsExactly(1L);
        verify(channels, org.mockito.Mockito.never()).publish(any());
        verify(transactions).commit(status);
    }
}
