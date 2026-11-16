package top.yealike.ipe.agent.runtime.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import top.yealike.ipe.agent.runtime.prepare.PreparedRelease;
import top.yealike.ipe.ai.contract.RunDispatchRequest;

class RunExecutionTest {

    @Test
    void queuedCancellationWinsAgainstWorkerStart() {
        RunExecution execution =
                new RunExecution(mock(RunDispatchRequest.class), mock(PreparedRelease.class), "runtime-test");

        assertThat(execution.tryCancelQueued()).isTrue();
        assertThat(execution.markWorkerStarted()).isFalse();
        assertThat(execution.markTerminal()).isFalse();
    }

    @Test
    void startedWorkerOwnsFinalTerminalTransition() {
        RunExecution execution =
                new RunExecution(mock(RunDispatchRequest.class), mock(PreparedRelease.class), "runtime-test");

        assertThat(execution.markWorkerStarted()).isTrue();
        assertThat(execution.tryCancelQueued()).isFalse();
        assertThat(execution.markTerminal()).isTrue();
        assertThat(execution.markTerminal()).isFalse();
    }
}
