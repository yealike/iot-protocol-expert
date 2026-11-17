package top.yealike.pep.control.agents.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import top.yealike.pep.control.agents.dispatch.RuntimeDispatchClient;
import top.yealike.pep.control.agents.domain.PlatformRecords.McpRelease;
import top.yealike.pep.control.agents.domain.PlatformRecords.Publication;
import top.yealike.pep.control.agents.registry.NacosRegistryService;
import top.yealike.pep.control.agents.registry.RegistryProjectionService;
import top.yealike.pep.control.agents.store.AgentStore;
import top.yealike.pep.control.agents.store.McpStore;
import top.yealike.pep.control.agents.store.PublicationStore;
import top.yealike.pep.control.agents.store.SkillStore;

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
    void successfulPublicationCommitsOnce() {
        Fixture fixture = new Fixture(true);

        fixture.coordinator.tick();

        assertThat(fixture.transactions.commits).isEqualTo(1);
        assertThat(fixture.transactions.rollbacks).isZero();
    }

    private static final class Fixture {

        private final PublicationStore publications = mock(PublicationStore.class);
        private final McpStore mcps = mock(McpStore.class);
        private final SkillStore skills = mock(SkillStore.class);
        private final AgentStore agents = mock(AgentStore.class);
        private final NacosRegistryService registry = mock(NacosRegistryService.class);
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
                    publications,
                    mcps,
                    skills,
                    agents,
                    mock(RuntimeDispatchClient.class),
                    registry,
                    projections,
                    transactions);
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
