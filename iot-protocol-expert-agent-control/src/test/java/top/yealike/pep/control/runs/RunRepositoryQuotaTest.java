package top.yealike.pep.control.runs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
import org.mockito.InOrder;
import top.yealike.pep.control.runs.RunRecords.Run;
import top.yealike.pep.control.runs.mapper.RunMapper;

class RunRepositoryQuotaTest {

    private final RunMapper mapper = mock(RunMapper.class);
    private final RunRepository repository = new RunRepository(mapper);

    @Test
    void dispatchLocksUserAndRechecksActiveBeforeClaim() {
        Run candidate = run();
        when(mapper.selectNextQueued(1)).thenReturn(List.of(candidate.id()));
        when(mapper.find(candidate.id())).thenReturn(List.of(candidate));
        when(mapper.countUserActive("user", null)).thenReturn(0);
        when(mapper.claimForDispatch(eq("control-1"), eq(30), any(), eq(candidate.id())))
                .thenReturn(1);

        Optional<Run> claimed =
                repository.claimNextForDispatch("control-1", 30, Instant.now().plusSeconds(60), 1);

        assertThat(claimed).contains(candidate);
        InOrder order = inOrder(mapper);
        order.verify(mapper).selectNextQueued(1);
        order.verify(mapper).lockQuota("ai-run-dispatch:user:user");
        order.verify(mapper).countUserActive("user", null);
        order.verify(mapper).claimForDispatch(eq("control-1"), eq(30), any(), eq(candidate.id()));
    }

    @Test
    void dispatchStopsWhenUserAlreadyHasActiveRun() {
        Run candidate = run();
        when(mapper.selectNextQueued(1)).thenReturn(List.of(candidate.id()));
        when(mapper.find(candidate.id())).thenReturn(List.of(candidate));
        when(mapper.countUserActive("user", null)).thenReturn(1);

        Optional<Run> claimed =
                repository.claimNextForDispatch("control-2", 30, Instant.now().plusSeconds(60), 1);

        assertThat(claimed).isEmpty();
        verify(mapper, never()).claimForDispatch(any(), anyInt(), any(), any());
    }

    private static Run run() {
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
                "QUEUED",
                100,
                1L,
                "idempotency",
                "v1",
                "00-trace",
                null,
                null,
                Map.of(),
                Map.of(),
                null,
                null,
                null,
                null,
                now,
                now,
                UUID.randomUUID(),
                "digest",
                null,
                0L,
                null,
                0,
                now.plusSeconds(120),
                null,
                null);
    }
}
