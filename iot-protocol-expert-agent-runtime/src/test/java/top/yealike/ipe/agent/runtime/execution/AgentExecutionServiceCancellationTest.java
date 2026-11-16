package top.yealike.ipe.agent.runtime.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.yealike.ipe.agent.runtime.TestFixtures;
import top.yealike.ipe.agent.runtime.config.RuntimeProperties;
import top.yealike.ipe.agent.runtime.control.ControlClient;
import top.yealike.ipe.agent.runtime.observability.RuntimeObservability;
import top.yealike.ipe.agent.runtime.prepare.PreparedRelease;
import top.yealike.ipe.agent.runtime.prepare.PreparedReleaseRegistry;
import top.yealike.ipe.agent.runtime.registry.NacosRuntimeRegistry;
import top.yealike.ipe.ai.contract.AgentManifest;
import top.yealike.ipe.ai.contract.ManifestDigests;
import top.yealike.ipe.ai.contract.RunClaimResponse;
import top.yealike.ipe.ai.contract.RunCompletionRequest;
import top.yealike.ipe.ai.contract.RunDispatchRequest;
import top.yealike.ipe.ai.contract.RunTerminalStatus;
import top.yealike.ipe.ai.contract.RuntimeRunStatus;

class AgentExecutionServiceCancellationTest {

    @Test
    void cancellingQueuedRunCancelsFutureAndReleasesAdmission() throws Exception {
        RuntimeProperties properties = TestFixtures.properties();
        ControlClient control = mock(ControlClient.class);
        PreparedReleaseRegistry releases = new PreparedReleaseRegistry();
        RunExecutionRegistry executions = new RunExecutionRegistry();
        RunAdmission admission = new RunAdmission(properties);
        NacosRuntimeRegistry nacos = mock(NacosRuntimeRegistry.class);
        when(nacos.instanceId()).thenReturn("runtime-test");

        AgentManifest manifest = TestFixtures.manifestWithoutMcp(List.of());
        UUID releaseId = UUID.randomUUID();
        String digest = ManifestDigests.digest(manifest);
        releases.put(new PreparedRelease(
                releaseId,
                digest,
                1L,
                UUID.randomUUID(),
                manifest,
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                Instant.now()));

        UUID runId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        long token = 99L;
        Instant deadline = Instant.now().plusSeconds(60);
        when(control.claim(any()))
                .thenReturn(new RunClaimResponse(
                        true,
                        "OK",
                        "runtime-test",
                        deadline,
                        false,
                        conversationId,
                        "actor",
                        1L,
                        List.of(),
                        "",
                        manifest.budget()));

        ThreadPoolExecutor runExecutor =
                new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8));
        ScheduledThreadPoolExecutor heartbeat = daemonScheduler();
        ScheduledThreadPoolExecutor events = daemonScheduler();
        CountDownLatch blocker = new CountDownLatch(1);
        runExecutor.submit(() -> {
            try {
                blocker.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        AgentExecutionService service = new AgentExecutionService(
                properties,
                control,
                releases,
                executions,
                admission,
                mock(AgentRuntimeFactory.class),
                mock(RunMessageMapper.class),
                nacos,
                runExecutor,
                heartbeat,
                events,
                metrics,
                mock(RuntimeObservability.class));
        try {
            RunDispatchRequest request = new RunDispatchRequest(
                    runId,
                    releaseId,
                    digest,
                    token,
                    manifest.agentKey(),
                    manifest.version(),
                    conversationId,
                    "actor",
                    1L,
                    deadline,
                    "control",
                    null);
            service.dispatch(manifest.agentKey(), manifest.version(), request);

            RuntimeRunStatus before = service.status(runId, token);
            assertThat(before.status()).isEqualTo("QUEUED");
            assertThat(admission.available()).isEqualTo(admission.capacity() - 1);

            service.cancel(runId, token);

            assertThat(service.status(runId, token).status()).isEqualTo("CANCELLED");
            assertThat(admission.available()).isEqualTo(admission.capacity());
            assertThat(metrics.get("run_active").gauge().value()).isZero();

            ArgumentCaptor<RunCompletionRequest> completion = ArgumentCaptor.forClass(RunCompletionRequest.class);
            verify(control, timeout(2_000)).complete(completion.capture());
            assertThat(completion.getValue().status()).isEqualTo(RunTerminalStatus.CANCELLED);
            assertThat(completion.getValue().runId()).isEqualTo(runId);
        } finally {
            blocker.countDown();
            runExecutor.shutdownNow();
            heartbeat.shutdownNow();
            events.shutdownNow();
        }
    }

    private static ScheduledThreadPoolExecutor daemonScheduler() {
        return new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            return thread;
        });
    }
}
