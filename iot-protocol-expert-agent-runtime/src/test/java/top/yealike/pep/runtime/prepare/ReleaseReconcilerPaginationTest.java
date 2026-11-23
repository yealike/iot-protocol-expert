package top.yealike.pep.runtime.prepare;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import top.yealike.pep.runtime.control.ControlClient;
import top.yealike.pep.runtime.control.dto.DesiredRelease;
import top.yealike.pep.runtime.control.dto.DesiredReleasePage;

class ReleaseReconcilerPaginationTest {

    @ParameterizedTest
    @CsvSource({"0,1", "199,1", "200,1", "201,2", "400,2", "401,3"})
    void stopsAtServerPageBoundary(int total, int expectedCalls) {
        ControlClient control = mock(ControlClient.class);
        PrepareService prepareService = mock(PrepareService.class);
        ExecutorService executor = synchronousExecutor();
        List<DesiredRelease> releases = releases(total);
        when(control.desiredReleases(anyLong())).thenAnswer(invocation -> page(releases, invocation.getArgument(0)));

        ReleaseReconciler reconciler = new ReleaseReconciler(control, prepareService, executor);
        reconciler.start();

        verify(control, times(expectedCalls)).desiredReleases(anyLong());
    }

    @Test
    void stopsWhenServerKeepsNonAdvancingCursor() {
        ControlClient control = mock(ControlClient.class);
        PrepareService prepareService = mock(PrepareService.class);
        ExecutorService executor = synchronousExecutor();
        List<DesiredRelease> first = releases(200);
        when(control.desiredReleases(0L)).thenReturn(new DesiredReleasePage(first, 200L, 200, true));
        when(control.desiredReleases(200L)).thenReturn(new DesiredReleasePage(List.of(), 200L, 200, true));

        ReleaseReconciler reconciler = new ReleaseReconciler(control, prepareService, executor);
        reconciler.start();

        verify(control, times(1)).desiredReleases(0L);
        verify(control, times(1)).desiredReleases(200L);
        verify(control, times(2)).desiredReleases(anyLong());
    }

    @Test
    void nextScheduledCycleCanReconcileAgain() {
        ControlClient control = mock(ControlClient.class);
        PrepareService prepareService = mock(PrepareService.class);
        ExecutorService executor = synchronousExecutor();
        when(control.desiredReleases(anyLong())).thenReturn(new DesiredReleasePage(List.of(), 0L, 200, false));

        ReleaseReconciler reconciler = new ReleaseReconciler(control, prepareService, executor);
        reconciler.start();
        reconciler.scheduledReconcile();

        verify(control, times(2)).desiredReleases(anyLong());
    }

    private static ExecutorService synchronousExecutor() {
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.submit(any(Runnable.class))).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        });
        return executor;
    }

    private static DesiredReleasePage page(List<DesiredRelease> releases, long cursor) {
        int start = (int) Math.min(cursor, releases.size());
        int end = Math.min(start + 200, releases.size());
        List<DesiredRelease> items = List.copyOf(releases.subList(start, end));
        long nextCursor = items.isEmpty() ? cursor : end;
        return new DesiredReleasePage(items, nextCursor, 200, end < releases.size());
    }

    private static List<DesiredRelease> releases(int total) {
        List<DesiredRelease> releases = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            releases.add(new DesiredRelease(
                    UUID.randomUUID(), "agent-" + index, "1.0.0", "digest-" + index, index + 1L, "ACTIVE"));
        }
        return releases;
    }
}
