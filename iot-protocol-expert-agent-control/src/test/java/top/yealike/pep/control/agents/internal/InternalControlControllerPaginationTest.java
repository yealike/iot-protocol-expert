package top.yealike.pep.control.agents.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.yealike.pep.control.agents.AgentService;
import top.yealike.pep.control.agents.domain.PlatformRecords.AgentRelease;
import top.yealike.pep.control.agents.mapper.InternalControlMapper;
import top.yealike.pep.control.agents.mcp.McpService;
import top.yealike.pep.control.agents.skill.SkillService;
import top.yealike.pep.control.agents.store.AgentStore;
import top.yealike.pep.runtime.contract.AgentManifest;

class InternalControlControllerPaginationTest {

    @Test
    void exactlyTwoHundredItemsEndsWithoutHasMore() {
        InternalControlController controller = controllerWith(releases(200));

        Map<String, Object> page = controller.desiredReleases(null, 200);

        assertThat(items(page)).hasSize(200);
        assertThat(page.get("cursor")).isEqualTo(200L);
        assertThat(page.get("hasMore")).isEqualTo(false);
    }

    @Test
    void fourHundredAndOneItemsUseThreePages() {
        AgentStore store = mock(AgentStore.class);
        when(store.desiredReleases()).thenReturn(releases(401));
        InternalControlController controller = controller(store);

        Map<String, Object> first = controller.desiredReleases(null, 200);
        Map<String, Object> second = controller.desiredReleases(200L, 200);
        Map<String, Object> third = controller.desiredReleases(400L, 200);

        assertThat(items(first)).hasSize(200);
        assertThat(first.get("hasMore")).isEqualTo(true);
        assertThat(second.get("cursor")).isEqualTo(400L);
        assertThat(second.get("hasMore")).isEqualTo(true);
        assertThat(items(third)).hasSize(1);
        assertThat(third.get("cursor")).isEqualTo(401L);
        assertThat(third.get("hasMore")).isEqualTo(false);
    }

    @Test
    void emptyLastPageKeepsRequestedCursorAndStops() {
        InternalControlController controller = controllerWith(releases(200));

        Map<String, Object> page = controller.desiredReleases(200L, 200);

        assertThat(items(page)).isEmpty();
        assertThat(page.get("cursor")).isEqualTo(200L);
        assertThat(page.get("hasMore")).isEqualTo(false);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private static InternalControlController controllerWith(List<AgentRelease> releases) {
        AgentStore store = mock(AgentStore.class);
        when(store.desiredReleases()).thenReturn(releases);
        return controller(store);
    }

    private static InternalControlController controller(AgentStore store) {
        return new InternalControlController(
                mock(AgentService.class),
                store,
                mock(McpService.class),
                mock(SkillService.class),
                mock(RuntimeRunCoordinator.class),
                mock(InternalControlMapper.class));
    }

    private static List<AgentRelease> releases(int total) {
        List<AgentRelease> releases = new ArrayList<>(total);
        Instant now = Instant.now();
        for (int index = 0; index < total; index++) {
            releases.add(new AgentRelease(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "agent-" + index,
                    "1.0.0",
                    mock(AgentManifest.class),
                    "digest-" + index,
                    "ACTIVE",
                    index + 1L,
                    index + 1L,
                    now));
        }
        return releases;
    }
}
