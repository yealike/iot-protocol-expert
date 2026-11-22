package top.yealike.pep.control.agents.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import top.yealike.pep.control.agents.domain.AgentRelease;
import top.yealike.pep.control.agents.domain.McpRelease;
import top.yealike.pep.control.agents.domain.Publication;
import top.yealike.pep.control.agents.registry.RegistryProjectionService;
import top.yealike.pep.control.agents.store.AgentStore;
import top.yealike.pep.control.agents.store.McpStore;
import top.yealike.pep.control.agents.store.PublicationStore;
import top.yealike.pep.control.agents.store.SkillStore;
import top.yealike.pep.control.config.properties.PlatformProperties;
import top.yealike.pep.control.config.properties.PlatformRuntime;
import top.yealike.pep.control.config.properties.PlatformSecurity;
import top.yealike.pep.control.integration.runtime.RuntimeDispatchClient;
import top.yealike.pep.control.integration.runtime.dto.PrepareResult;
import top.yealike.pep.control.integration.runtime.dto.RuntimeTarget;
import top.yealike.pep.runtime.contract.InternalPaths;
import top.yealike.pep.runtime.contract.ReleasePrepareRequest;

class PublicationCoordinatorTransactionTest {

    @Test
    void publicationCasFailureRollsBackActivationAndPointer() {
        Fixture fixture = new Fixture(false);

        fixture.coordinator.tick();

        assertThat(fixture.transactions.commits).isZero();
        assertThat(fixture.transactions.rollbacks).isEqualTo(1);
        verify(fixture.mcps).setReleaseState(fixture.release.id(), "ACTIVE");
        verify(fixture.mcps).publishComponent(fixture.release.componentId(), "1.0.0", 7L);
        verify(fixture.publications)
                .advance(
                        eq(fixture.publication.id()),
                        anyString(),
                        eq("REGISTERING"),
                        eq("PUBLISHED"),
                        eq("PUBLISHED"),
                        isNull(),
                        isNull());
    }

    @Test
    void successfulPublicationCommitsEvenWithoutNacosComponentInstances() {
        Fixture fixture = new Fixture(true);

        fixture.coordinator.tick();

        assertThat(fixture.transactions.commits).isEqualTo(1);
        assertThat(fixture.transactions.rollbacks).isZero();
    }

    @Test
    void savesSpecificRuntimePreparationRejectionFromHttpWithoutRetrying() throws Exception {
        var fixture = new Fixture(false);
        var now = Instant.now();
        var publication = new Publication(
                UUID.randomUUID(),
                "AGENT",
                fixture.resourceId,
                "1.1.3",
                1L,
                "PREPARING",
                "PREPARING",
                1L,
                "test",
                null,
                null,
                0,
                now,
                now.plusSeconds(60),
                now,
                now);
        var release = new AgentRelease(
                UUID.randomUUID(), fixture.resourceId, "demo", "1.1.3", null, "digest", "STAGED", 1L, 1L, now);
        when(fixture.publications.claimPending(anyString(), anyInt(), anyInt())).thenReturn(List.of(publication));
        when(fixture.agents.releases(fixture.resourceId)).thenReturn(List.of(release));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String reason = "MCP 工具 Schema 已漂移：demo.read_document";
        server.createContext(InternalPaths.RUNTIME_PREPARE, exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                var body = ("{\"error\":\"SCHEMA_DRIFT\",\"message\":\"" + reason + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(422, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var properties = new PlatformProperties(
                    null,
                    new PlatformSecurity(null, null, "control", "runtime", "test-key", "test", 15),
                    new PlatformRuntime(
                            3,
                            30,
                            100,
                            2,
                            1,
                            "http://127.0.0.1:" + server.getAddress().getPort()),
                    null);
            var runtime = new RuntimeDispatchClient(properties);
            var coordinator = new PublicationCoordinator(
                    fixture.publications,
                    fixture.mcps,
                    fixture.skills,
                    fixture.agents,
                    runtime,
                    fixture.projections,
                    fixture.transactions);

            coordinator.tick();

            verify(fixture.publications)
                    .markFailed(eq(publication.id()), anyString(), eq("PREPARING"), eq("SCHEMA_DRIFT"), eq(reason));
            verify(fixture.publications, never())
                    .fail(eq(publication.id()), anyString(), anyString(), anyString(), anyString(), any());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resumesLegacyRegisteringPublicationAfterDirectRuntimeIsReady() {
        var fixture = new Fixture(true);
        var now = Instant.now();
        var publication = new Publication(
                UUID.randomUUID(),
                "AGENT",
                fixture.resourceId,
                "1.1.3",
                1L,
                "REGISTERING",
                "REGISTERING",
                1L,
                "test",
                null,
                null,
                0,
                now,
                now.plusSeconds(60),
                now,
                now);
        var release = new AgentRelease(
                UUID.randomUUID(), fixture.resourceId, "demo", "1.1.3", null, "digest", "STAGED", 1L, 1L, now);
        var runtime = mock(RuntimeDispatchClient.class);
        var target = new RuntimeTarget("http://localhost:19084", "direct", "direct");
        var request = new ReleasePrepareRequest(release.id(), "digest", 1L, publication.id());
        when(runtime.targets()).thenReturn(List.of(target));
        when(runtime.prepare(target, request))
                .thenReturn(new PrepareResult(true, "READY", "runtime-1", null, null, 200));
        when(fixture.publications.claimPending(anyString(), anyInt(), anyInt())).thenReturn(List.of(publication));
        when(fixture.agents.releases(fixture.resourceId)).thenReturn(List.of(release));
        when(fixture.agents.setReleaseState(release.id(), "ACTIVE")).thenReturn(1);
        when(fixture.agents.publishComponent(fixture.resourceId, "1.1.3", 1L)).thenReturn(true);
        when(fixture.publications.advance(
                        eq(publication.id()),
                        anyString(),
                        eq("REGISTERING"),
                        eq("PUBLISHED"),
                        eq("PUBLISHED"),
                        isNull(),
                        isNull()))
                .thenReturn(true);
        var coordinator = new PublicationCoordinator(
                fixture.publications,
                fixture.mcps,
                fixture.skills,
                fixture.agents,
                runtime,
                fixture.projections,
                fixture.transactions);

        coordinator.tick();

        verify(runtime).prepare(target, request);
        verify(fixture.agents).setReleaseState(release.id(), "ACTIVE");
        assertThat(fixture.transactions.commits).isEqualTo(1);
    }

    private static final class Fixture {

        private final PublicationStore publications = mock(PublicationStore.class);
        private final McpStore mcps = mock(McpStore.class);
        private final SkillStore skills = mock(SkillStore.class);
        private final AgentStore agents = mock(AgentStore.class);
        private final RegistryProjectionService projections = mock(RegistryProjectionService.class);
        private final RecordingTransactionManager transactions = new RecordingTransactionManager();
        private final UUID resourceId = UUID.randomUUID();
        private final McpRelease release = new McpRelease(
                UUID.randomUUID(),
                resourceId,
                "ops",
                "1.0.0",
                "STREAMABLE_HTTP",
                java.util.Map.of("endpoint", "http://127.0.0.1:9000/mcp", "path", "/mcp"),
                null,
                List.of(),
                "catalog",
                "content",
                "STAGED",
                7L,
                Instant.now());
        private final Publication publication = new Publication(
                UUID.randomUUID(),
                "MCP",
                resourceId,
                "1.0.0",
                1L,
                "REGISTERING",
                "REGISTERING",
                7L,
                "key",
                null,
                null,
                0,
                Instant.now(),
                Instant.now().plusSeconds(60),
                Instant.now(),
                Instant.now());
        private final PublicationCoordinator coordinator;

        private Fixture(boolean complete) {
            when(publications.claimOutbox(anyString(), anyInt(), anyInt())).thenReturn(List.of());
            when(publications.claimPending(anyString(), anyInt(), anyInt())).thenReturn(List.of(publication));
            when(publications.renewLease(any(), anyString(), anyInt())).thenReturn(true);
            when(mcps.releases(resourceId)).thenReturn(List.of(release));
            when(mcps.setReleaseState(release.id(), "ACTIVE")).thenReturn(1);
            when(mcps.publishComponent(resourceId, "1.0.0", 7L)).thenReturn(true);
            when(publications.advance(
                            eq(publication.id()),
                            anyString(),
                            eq("REGISTERING"),
                            eq("PUBLISHED"),
                            eq("PUBLISHED"),
                            isNull(),
                            isNull()))
                    .thenReturn(complete);
            when(publications.fail(any(), anyString(), anyString(), anyString(), anyString(), any()))
                    .thenReturn(false);
            coordinator = new PublicationCoordinator(
                    publications, mcps, skills, agents, mock(RuntimeDispatchClient.class), projections, transactions);
        }
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        private int commits;
        private int rollbacks;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            if (status.isRollbackOnly()) {
                rollbacks++;
            } else {
                commits++;
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbacks++;
        }
    }
}
