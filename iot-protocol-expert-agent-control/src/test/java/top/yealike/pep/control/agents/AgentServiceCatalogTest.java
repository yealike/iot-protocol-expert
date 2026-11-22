package top.yealike.pep.control.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.yealike.pep.control.access.policy.PolicyService;
import top.yealike.pep.control.agents.domain.PlatformRecords.AgentCatalogItem;
import top.yealike.pep.control.agents.domain.PlatformRecords.AgentRelease;
import top.yealike.pep.control.agents.store.AgentStore;
import top.yealike.pep.control.agents.store.McpStore;
import top.yealike.pep.control.agents.store.PublicationStore;
import top.yealike.pep.control.agents.store.SkillStore;
import top.yealike.pep.runtime.contract.AgentManifest;
import top.yealike.pep.runtime.contract.UserIdentity;

class AgentServiceCatalogTest {

    @Test
    void catalogUsesDefaultReleasesAndCollapsesDuplicateKeys() {
        AgentStore agents = mock(AgentStore.class);
        PolicyService policy = mock(PolicyService.class);
        AgentRelease v2 = release("demo-agent", "2.0.0", 2L);
        AgentRelease staleV1 = release("demo-agent", "1.0.0", 1L);
        when(agents.defaultReleases()).thenReturn(List.of(v2, staleV1));
        when(policy.canUseAgent(any(), eq("demo-agent"))).thenReturn(new PolicyService.Decision(true, "OK", "v1"));
        AgentService service = new AgentService(
                agents,
                mock(McpStore.class),
                mock(SkillStore.class),
                mock(AgentCompiler.class),
                mock(PublicationStore.class),
                policy);

        List<AgentCatalogItem> catalog =
                service.catalog(new UserIdentity("user-1", "user-1", List.of("ai:user"), List.of(), 1L));

        assertThat(catalog).hasSize(1);
        assertThat(catalog.getFirst().agentKey()).isEqualTo("demo-agent");
        assertThat(catalog.getFirst().version()).isEqualTo("2.0.0");
        verify(agents, never()).desiredReleases();
    }

    @Test
    void oldConversationKeepsPinnedReleaseWhileNewConversationUsesDefault() {
        AgentStore agents = mock(AgentStore.class);
        AgentRelease v2 = release("demo-agent", "2.0.0", 2L);
        AgentRelease v1 = release("demo-agent", "1.0.0", 1L);
        when(agents.findPublishedRelease("demo-agent")).thenReturn(Optional.of(v2));
        when(agents.findRelease("demo-agent", "1.0.0")).thenReturn(Optional.of(v1));
        AgentService service = new AgentService(
                agents,
                mock(McpStore.class),
                mock(SkillStore.class),
                mock(AgentCompiler.class),
                mock(PublicationStore.class),
                mock(PolicyService.class));

        assertThat(service.requirePublishedRelease("demo-agent", null)).isSameAs(v2);
        assertThat(service.requirePublishedRelease("demo-agent", "1.0.0")).isSameAs(v1);
    }

    private static AgentRelease release(String key, String version, long cursor) {
        AgentManifest manifest = new AgentManifest(
                "1",
                key,
                version,
                "Demo",
                "prompt",
                null,
                List.of(),
                List.of(),
                new AgentManifest.Budget(6, 12, 120_000L),
                new AgentManifest.ExecutionPolicy(false, "DENY"));
        return new AgentRelease(
                UUID.randomUUID(),
                UUID.randomUUID(),
                key,
                version,
                manifest,
                "digest-" + version,
                "ACTIVE",
                cursor,
                cursor,
                Instant.now());
    }
}
