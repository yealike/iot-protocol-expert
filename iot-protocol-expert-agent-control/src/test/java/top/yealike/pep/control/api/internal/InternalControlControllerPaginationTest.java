package top.yealike.pep.control.api.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.yealike.pep.control.agents.AgentService;
import top.yealike.pep.control.agents.domain.AgentRelease;
import top.yealike.pep.control.agents.mcp.McpService;
import top.yealike.pep.control.agents.skill.SkillService;
import top.yealike.pep.control.agents.store.AgentStore;
import top.yealike.pep.control.api.internal.mapper.InternalControlMapper;
import top.yealike.pep.control.runs.runtime.RuntimeRunCoordinator;
import top.yealike.pep.runtime.contract.AgentManifest;
import top.yealike.pep.runtime.contract.DesiredRelease;
import top.yealike.pep.runtime.contract.DesiredReleasePage;

class InternalControlControllerPaginationTest {

    @Test
    void exactlyTwoHundredItemsEndsWithoutHasMore() {
        InternalControlController controller = controllerWith(releases(200));

        DesiredReleasePage page = controller.desiredReleases(null, 200);

        assertThat(items(page)).hasSize(200);
        assertThat(page.cursor()).isEqualTo(200L);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void fourHundredAndOneItemsUseThreePages() {
        AgentStore store = mock(AgentStore.class);
        when(store.desiredReleases()).thenReturn(releases(401));
        InternalControlController controller = controller(store);

        DesiredReleasePage first = controller.desiredReleases(null, 200);
        DesiredReleasePage second = controller.desiredReleases(200L, 200);
        DesiredReleasePage third = controller.desiredReleases(400L, 200);

        assertThat(items(first)).hasSize(200);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.cursor()).isEqualTo(400L);
        assertThat(second.hasMore()).isTrue();
        assertThat(items(third)).hasSize(1);
        assertThat(third.cursor()).isEqualTo(401L);
        assertThat(third.hasMore()).isFalse();
    }

    @Test
    void emptyLastPageKeepsRequestedCursorAndStops() {
        InternalControlController controller = controllerWith(releases(200));

        DesiredReleasePage page = controller.desiredReleases(200L, 200);

        assertThat(items(page)).isEmpty();
        assertThat(page.cursor()).isEqualTo(200L);
        assertThat(page.hasMore()).isFalse();
    }

    private static List<DesiredRelease> items(DesiredReleasePage page) {
        return page.items();
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
