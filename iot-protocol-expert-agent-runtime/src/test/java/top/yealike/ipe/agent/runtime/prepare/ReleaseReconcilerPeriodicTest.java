package top.yealike.ipe.agent.runtime.prepare;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import top.yealike.ipe.agent.runtime.control.ControlClient;
import top.yealike.ipe.agent.runtime.control.DesiredReleasePage;
import top.yealike.ipe.agent.runtime.registry.NacosRuntimeRegistry;

class ReleaseReconcilerPeriodicTest {

    @Test
    void shouldRejectOverlappingReconciles() {
        ControlClient control = mock(ControlClient.class);
        PrepareService prepareService = mock(PrepareService.class);
        NacosRuntimeRegistry registry = mock(NacosRuntimeRegistry.class);
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.submit(any(Runnable.class))).thenAnswer(invocation -> null);
        when(registry.ready()).thenReturn(true);
        when(control.desiredReleases()).thenReturn(new DesiredReleasePage(List.of(), 0L, 100));

        ReleaseReconciler reconciler = new ReleaseReconciler(control, prepareService, registry, executor);
        reconciler.start();
        reconciler.scheduledReconcile();

        verify(executor, times(1)).submit(any(Runnable.class));
    }

    @Test
    void shouldResetSingleFlightWhenSubmissionRejected() {
        ControlClient control = mock(ControlClient.class);
        PrepareService prepareService = mock(PrepareService.class);
        NacosRuntimeRegistry registry = mock(NacosRuntimeRegistry.class);
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException("busy"));

        ReleaseReconciler reconciler = new ReleaseReconciler(control, prepareService, registry, executor);
        reconciler.start();

        verify(executor, times(1)).submit(any(Runnable.class));
    }
}
