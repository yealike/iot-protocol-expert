package top.yealike.pep.control.runs.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import top.yealike.pep.runtime.contract.ErrorCodes;
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
        when(runs.reserveEventCursors(conversationId, 3)).thenReturn(10L);
        when(runs.lockRuntimeEventRun(runId, 7L, "runtime-test")).thenReturn(true);
        when(runs.insertRuntimeEvents(anyList())).thenReturn(Set.of(1L, 2L, 3L));

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
        RunEvent arguments = new RunEvent(
                "tool.arguments",
                Map.of(
                        "toolAlias",
                        "mcp_ops__get_device_detail",
                        "toolCallId",
                        "call-1",
                        "arguments",
                        Map.of("deviceId", "synthetic-device")),
                2L,
                UUID.randomUUID(),
                startedAt.plusMillis(10));
        RunEvent completed = new RunEvent(
                "tool.completed",
                Map.of(
                        "toolAlias",
                        "mcp_ops__get_device_detail",
                        "toolCallId",
                        "call-1",
                        "durationMs",
                        25,
                        "resultSummary",
                        Map.of("text", "deviceCount=0 marker=FLOW_OK", "truncated", false)),
                3L,
                UUID.randomUUID(),
                finishedAt);
        RunEventBatch batch =
                new RunEventBatch(runId, 7L, "runtime-test", "request-1", List.of(started, arguments, completed));

        RunEventAck ack = new RuntimeEventIngestService(runs, channels, transactions).ingest(conversationId, batch);

        assertThat(ack.acceptedSourceSeq()).containsExactly(1L, 2L, 3L);
        assertThat(ack.lastCursor()).isEqualTo(10L);
        ArgumentCaptor<List<RuntimeEventInsert>> inserts = ArgumentCaptor.forClass(List.class);
        verify(runs).insertRuntimeEvents(inserts.capture());
        assertThat(inserts.getValue()).extracting(RuntimeEventInsert::cursor).containsExactly(8L, 9L, 10L);
        verify(channels, times(3)).publish(any());
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
                .updateRuntimeToolArguments(
                        eq(toolCallId),
                        eq(runId),
                        eq(Map.of("text", "{\"deviceId\":\"synthetic-device\"}", "truncated", false)));
        verify(runs)
                .finishRuntimeToolCall(
                        eq(toolCallId),
                        eq(runId),
                        eq("SUCCEEDED"),
                        eq("ALLOW"),
                        isNull(),
                        eq(Map.of("text", "deviceCount=0 marker=FLOW_OK", "truncated", false)),
                        eq(25),
                        eq(finishedAt));
    }

    @Test
    void recordsDeniedToolAuthorizationFromTheTerminalEvent() {
        RunRepository runs = mock(RunRepository.class);
        ConversationChannels channels = mock(ConversationChannels.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(status);

        UUID conversationId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant finishedAt = Instant.parse("2026-11-16T05:00:01Z");
        when(runs.reserveEventCursors(conversationId, 1)).thenReturn(9L);
        when(runs.lockRuntimeEventRun(runId, 7L, "runtime-test")).thenReturn(true);
        when(runs.insertRuntimeEvents(anyList())).thenReturn(Set.of(3L));
        RunEvent denied = new RunEvent(
                "tool.failed",
                Map.of(
                        "toolAlias",
                        "mcp_ops__get_device_detail",
                        "toolCallId",
                        "call-denied",
                        "errorCode",
                        ErrorCodes.TOOL_DENIED,
                        "decision",
                        "DENIED",
                        "resultSummary",
                        Map.of("text", "工具授权校验失败", "truncated", false)),
                3L,
                UUID.randomUUID(),
                finishedAt);

        new RuntimeEventIngestService(runs, channels, transactions)
                .ingest(
                        conversationId,
                        new RunEventBatch(runId, 7L, "runtime-test", "request-denied", List.of(denied)));

        UUID toolCallId = UUID.nameUUIDFromBytes((runId + ":call-denied").getBytes(StandardCharsets.UTF_8));
        verify(runs)
                .finishRuntimeToolCall(
                        eq(toolCallId),
                        eq(runId),
                        eq("FAILED"),
                        eq("DENIED"),
                        eq("工具授权校验失败"),
                        eq(Map.of("text", "工具授权校验失败", "truncated", false)),
                        isNull(),
                        eq(finishedAt));
    }

    @Test
    void usesTheRuntimeDecisionInsteadOfInferringFromAGenericToolError() {
        RunRepository runs = mock(RunRepository.class);
        ConversationChannels channels = mock(ConversationChannels.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(status);

        UUID conversationId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant finishedAt = Instant.parse("2026-11-16T05:00:01Z");
        when(runs.reserveEventCursors(conversationId, 1)).thenReturn(9L);
        when(runs.lockRuntimeEventRun(runId, 7L, "runtime-test")).thenReturn(true);
        when(runs.insertRuntimeEvents(anyList())).thenReturn(Set.of(3L));
        RunEvent failedAfterAuthorization = new RunEvent(
                "tool.failed",
                Map.of(
                        "toolAlias",
                        "mcp_ops__get_device_detail",
                        "toolCallId",
                        "call-authorized-failure",
                        "errorCode",
                        ErrorCodes.MCP_TOOL_ERROR,
                        "decision",
                        "ALLOW",
                        "resultSummary",
                        Map.of("text", "upstream unavailable", "truncated", false)),
                3L,
                UUID.randomUUID(),
                finishedAt);

        new RuntimeEventIngestService(runs, channels, transactions)
                .ingest(
                        conversationId,
                        new RunEventBatch(
                                runId,
                                7L,
                                "runtime-test",
                                "request-authorized-failure",
                                List.of(failedAfterAuthorization)));

        UUID toolCallId = UUID.nameUUIDFromBytes((runId + ":call-authorized-failure").getBytes(StandardCharsets.UTF_8));
        verify(runs)
                .finishRuntimeToolCall(
                        eq(toolCallId),
                        eq(runId),
                        eq("FAILED"),
                        eq("ALLOW"),
                        isNull(),
                        eq(Map.of("text", "upstream unavailable", "truncated", false)),
                        isNull(),
                        eq(finishedAt));
    }

    @Test
    void boundsAndSanitizesDenyReasonForTheDatabaseColumn() {
        RunRepository runs = mock(RunRepository.class);
        ConversationChannels channels = mock(ConversationChannels.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(status);

        UUID conversationId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant finishedAt = Instant.parse("2026-11-16T05:00:01Z");
        when(runs.reserveEventCursors(conversationId, 1)).thenReturn(9L);
        when(runs.lockRuntimeEventRun(runId, 7L, "runtime-test")).thenReturn(true);
        when(runs.insertRuntimeEvents(anyList())).thenReturn(Set.of(3L));
        String reason = "password=never-store-this " + "拒绝原因".repeat(60);
        RunEvent denied = new RunEvent(
                "tool.failed",
                Map.of(
                        "toolAlias",
                        "mcp_ops__get_device_detail",
                        "toolCallId",
                        "call-long-denied",
                        "errorCode",
                        ErrorCodes.TOOL_DENIED,
                        "decision",
                        "DENIED",
                        "resultSummary",
                        Map.of("text", reason, "truncated", true)),
                3L,
                UUID.randomUUID(),
                finishedAt);

        new RuntimeEventIngestService(runs, channels, transactions)
                .ingest(
                        conversationId,
                        new RunEventBatch(runId, 7L, "runtime-test", "request-long-denied", List.of(denied)));

        ArgumentCaptor<String> denyReason = ArgumentCaptor.forClass(String.class);
        verify(runs)
                .finishRuntimeToolCall(
                        any(UUID.class),
                        eq(runId),
                        eq("FAILED"),
                        eq("DENIED"),
                        denyReason.capture(),
                        any(),
                        isNull(),
                        eq(finishedAt));
        assertThat(denyReason.getValue()).hasSizeLessThanOrEqualTo(128).doesNotContain("never-store-this");
    }

    @Test
    void leavesAuthorizationPendingWhenAToolIsInterruptedBeforeTheResult() {
        RunRepository runs = mock(RunRepository.class);
        ConversationChannels channels = mock(ConversationChannels.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(status);

        UUID conversationId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant finishedAt = Instant.parse("2026-11-16T05:00:01Z");
        when(runs.reserveEventCursors(conversationId, 1)).thenReturn(9L);
        when(runs.lockRuntimeEventRun(runId, 7L, "runtime-test")).thenReturn(true);
        when(runs.insertRuntimeEvents(anyList())).thenReturn(Set.of(3L));
        RunEvent timedOut = new RunEvent(
                "tool.failed",
                Map.of(
                        "toolAlias",
                        "mcp_ops__get_device_detail",
                        "toolCallId",
                        "call-timed-out",
                        "errorCode",
                        "TIMED_OUT"),
                3L,
                UUID.randomUUID(),
                finishedAt);

        new RuntimeEventIngestService(runs, channels, transactions)
                .ingest(
                        conversationId,
                        new RunEventBatch(runId, 7L, "runtime-test", "request-timeout", List.of(timedOut)));

        UUID toolCallId = UUID.nameUUIDFromBytes((runId + ":call-timed-out").getBytes(StandardCharsets.UTF_8));
        verify(runs)
                .finishRuntimeToolCall(
                        eq(toolCallId),
                        eq(runId),
                        eq("FAILED"),
                        isNull(),
                        isNull(),
                        eq(Map.of()),
                        isNull(),
                        eq(finishedAt));
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
