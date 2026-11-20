package top.yealike.pep.control.runs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.yealike.pep.control.access.policy.PolicyService;
import top.yealike.pep.control.agents.AgentService;
import top.yealike.pep.control.agents.config.PlatformProperties;
import top.yealike.pep.control.agents.dispatch.RuntimeDispatchClient;
import top.yealike.pep.control.config.AiProperties;
import top.yealike.pep.control.observability.AiObservability;
import top.yealike.pep.control.runs.RunRecords.Run;

class RunSchedulerLeaseTest {

    private final RunRepository runs = mock(RunRepository.class);
    private final RunEventService events = mock(RunEventService.class);
    private final AiObservability observability = mock(AiObservability.class);

    @Test
    void controlTerminatesDeadlineExpiredRunEvenWhenLeaseStillValid() {
        Run run = run("RUNNING", Instant.now().plusSeconds(60), Instant.now().minusSeconds(1), null);
        when(runs.claimNextForDispatch(anyString(), anyInt(), any(), anyInt())).thenReturn(Optional.empty());
        when(runs.dispatchRetries(10)).thenReturn(List.of());
        when(runs.expiredLeases(20)).thenReturn(List.of(run));
        when(runs.terminateExpired(
                        run.id(),
                        run.executionToken(),
                        run.runtimeInstanceId(),
                        "RUN_TIMED_OUT",
                        "Run 已超过执行截止时间，由 Control 终结"))
                .thenReturn("FAILED");
        when(runs.cancelPending(10, 20)).thenReturn(List.of());

        scheduler().tick();

        verify(runs)
                .terminateExpired(
                        run.id(),
                        run.executionToken(),
                        run.runtimeInstanceId(),
                        "RUN_TIMED_OUT",
                        "Run 已超过执行截止时间，由 Control 终结");
        verify(events).publish(eq(run.conversationId()), eq(run.id()), eq("run.failed"), anyMap());
    }

    @Test
    void cancelledRunWhoseRuntimeIsSilentConvergesToCancelled() {
        Run run = run(
                "CANCEL_REQUESTED", Instant.now().minusSeconds(1), Instant.now().plusSeconds(60), null);
        when(runs.claimNextForDispatch(anyString(), anyInt(), any(), anyInt())).thenReturn(Optional.empty());
        when(runs.dispatchRetries(10)).thenReturn(List.of());
        when(runs.expiredLeases(20)).thenReturn(List.of(run));
        when(runs.terminateExpired(
                        run.id(),
                        run.executionToken(),
                        run.runtimeInstanceId(),
                        "EXECUTION_LOST",
                        "执行租约已过期，Runtime 未继续回写进度"))
                .thenReturn("CANCELLED");
        when(runs.cancelPending(10, 20)).thenReturn(List.of());

        scheduler().tick();

        verify(events).publish(eq(run.conversationId()), eq(run.id()), eq("run.cancelled"), anyMap());
    }

    @Test
    void deferredDispatchIsNotKilledBeforeItsDeadline() {
        Run run = run(
                "DISPATCHING",
                Instant.now().minusSeconds(1),
                Instant.now().plusSeconds(60),
                Instant.now().plusSeconds(2));
        when(runs.claimNextForDispatch(anyString(), anyInt(), any(), anyInt())).thenReturn(Optional.empty());
        when(runs.dispatchRetries(10)).thenReturn(List.of());
        when(runs.expiredLeases(20)).thenReturn(List.of(run));
        when(runs.cancelPending(10, 20)).thenReturn(List.of());

        scheduler().tick();

        verify(runs, never())
                .terminateExpired(any(), org.mockito.ArgumentMatchers.anyLong(), any(), anyString(), anyString());
    }

    private RunScheduler scheduler() {
        when(observability.observeSupplier(anyString(), any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get());
        return new RunScheduler(
                runs,
                mock(AgentService.class),
                mock(PolicyService.class),
                events,
                mock(RuntimeDispatchClient.class),
                new PlatformProperties(
                        null, null, new PlatformProperties.Runtime(3, 30, 100, 2, 1, null, false), null, null),
                new AiProperties("test", null, 0, null, null, new AiProperties.Run(4, 6, 12, 120_000, 20), null),
                observability);
    }

    private static Run run(String status, Instant leaseUntil, Instant deadlineAt, Instant dispatchNextAttemptAt) {
        Instant now = Instant.now();
        return new Run(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "user",
                List.of("USER"),
                1L,
                "问题",
                "agent",
                "1.0.0",
                "model",
                status,
                100,
                1L,
                "idempotency",
                "v1",
                "00-trace",
                "runtime-1",
                leaseUntil,
                Map.of(),
                Map.of(),
                null,
                null,
                now,
                null,
                now,
                now,
                UUID.randomUUID(),
                "digest",
                "runtime-1",
                7L,
                now,
                1,
                deadlineAt,
                dispatchNextAttemptAt,
                "CANCEL_REQUESTED".equals(status) ? now : null);
    }
}
