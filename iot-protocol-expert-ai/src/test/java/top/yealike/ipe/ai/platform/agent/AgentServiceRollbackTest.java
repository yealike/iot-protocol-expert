package top.yealike.ipe.ai.platform.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.yealike.ipe.ai.contract.AgentManifest;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.AgentComponent;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.AgentRelease;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.Publication;
import top.yealike.ipe.ai.platform.registry.NacosRegistryService;
import top.yealike.ipe.ai.platform.store.AgentStore;
import top.yealike.ipe.ai.platform.store.McpStore;
import top.yealike.ipe.ai.platform.store.PublicationStore;
import top.yealike.ipe.ai.platform.store.SkillStore;
import top.yealike.ipe.ai.policy.PolicyService;

class AgentServiceRollbackTest {

    @Test
    void rollbackCreatesNewPublicationIntentWithoutMutatingDefaultPointer() {
        UUID agentId = UUID.randomUUID();
        UUID targetReleaseId = UUID.randomUUID();
        UUID publicationId = UUID.randomUUID();
        Instant now = Instant.now();
        AgentStore agents = mock(AgentStore.class);
        PublicationStore publications = mock(PublicationStore.class);
        AgentComponent component = new AgentComponent(
                agentId,
                "demo-agent",
                "Demo Agent",
                "",
                UUID.randomUUID(),
                "prompt",
                Map.of(),
                Map.of(),
                "2.0.0",
                20L,
                true,
                8L,
                "admin",
                now,
                now);
        AgentRelease target = new AgentRelease(
                targetReleaseId,
                agentId,
                "demo-agent",
                "1.0.0",
                mock(AgentManifest.class),
                "digest",
                "RETIRED",
                3L,
                1L,
                now);
        when(agents.lock(agentId)).thenReturn(Optional.of(component));
        when(agents.findRelease("demo-agent", "1.0.0")).thenReturn(Optional.of(target));
        when(agents.nextGeneration(agentId)).thenReturn(7L);
        when(publications.nextGeneration("AGENT", agentId)).thenReturn(11L);
        when(publications.insert(eq("AGENT"), eq(agentId), eq("1.0.0"), eq(8L), anyLong(), anyString(), any()))
                .thenReturn(publicationId);
        when(publications.findByIdempotency("AGENT", agentId, "rollback-key")).thenReturn(Optional.empty());

        AgentService service = new AgentService(
                agents,
                mock(McpStore.class),
                mock(SkillStore.class),
                mock(AgentCompiler.class),
                publications,
                mock(NacosRegistryService.class),
                mock(PolicyService.class));

        AgentService.RollbackResult result = service.rollback(agentId, 8L, "1.0.0", "rollback-key");

        assertThat(result.releaseId()).isEqualTo(targetReleaseId);
        assertThat(result.publicationId()).isEqualTo(publicationId);
        assertThat(result.version()).isEqualTo("1.0.0");
        assertThat(result.generation()).isEqualTo(21L);
        assertThat(result.state()).isEqualTo("VALIDATING");
        ArgumentCaptor<Long> generation = ArgumentCaptor.forClass(Long.class);
        verify(publications)
                .insert(eq("AGENT"), eq(agentId), eq("1.0.0"), eq(8L), generation.capture(), eq("rollback-key"), any());
        assertThat(generation.getValue()).isEqualTo(21L);
        verify(publications)
                .insertOutbox(eq("AGENT"), eq(targetReleaseId), eq(21L), eq("ROLLBACK_REQUESTED"), anyString());
        verify(agents, never()).publishComponent(any(), anyString(), anyLong());
        verify(agents, never()).setReleaseState(any(), anyString());
    }

    @Test
    void repeatedRollbackWithSameIdempotencyKeyReturnsExistingPublication() {
        UUID agentId = UUID.randomUUID();
        UUID targetReleaseId = UUID.randomUUID();
        UUID publicationId = UUID.randomUUID();
        Instant now = Instant.now();
        AgentStore agents = mock(AgentStore.class);
        PublicationStore publications = mock(PublicationStore.class);
        AgentComponent component = new AgentComponent(
                agentId,
                "demo-agent",
                "Demo Agent",
                "",
                UUID.randomUUID(),
                "prompt",
                Map.of(),
                Map.of(),
                "2.0.0",
                20L,
                true,
                9L,
                "admin",
                now,
                now);
        AgentRelease target = new AgentRelease(
                targetReleaseId,
                agentId,
                "demo-agent",
                "1.0.0",
                mock(AgentManifest.class),
                "digest",
                "RETIRED",
                3L,
                1L,
                now);
        Publication existing = new Publication(
                publicationId,
                "AGENT",
                agentId,
                "1.0.0",
                8L,
                "REGISTERING",
                "REGISTERING",
                21L,
                "rollback-key",
                null,
                null,
                0,
                now,
                now.plusSeconds(60),
                now,
                now);
        when(agents.lock(agentId)).thenReturn(Optional.of(component));
        when(agents.findRelease("demo-agent", "1.0.0")).thenReturn(Optional.of(target));
        when(publications.findByIdempotency("AGENT", agentId, "rollback-key")).thenReturn(Optional.of(existing));

        AgentService service = new AgentService(
                agents,
                mock(McpStore.class),
                mock(SkillStore.class),
                mock(AgentCompiler.class),
                publications,
                mock(NacosRegistryService.class),
                mock(PolicyService.class));

        AgentService.RollbackResult result = service.rollback(agentId, 9L, "1.0.0", "rollback-key");

        assertThat(result.publicationId()).isEqualTo(publicationId);
        assertThat(result.state()).isEqualTo("REGISTERING");
        verify(publications, never()).insert(anyString(), any(), anyString(), anyLong(), anyLong(), anyString(), any());
        verify(publications, never()).insertOutbox(anyString(), any(), anyLong(), anyString(), anyString());
    }
}
