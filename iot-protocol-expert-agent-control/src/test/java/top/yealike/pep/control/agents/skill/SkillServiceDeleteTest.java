package top.yealike.pep.control.agents.skill;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.yealike.pep.control.agents.domain.SkillComponent;
import top.yealike.pep.control.agents.domain.SkillRelease;
import top.yealike.pep.control.agents.store.PublicationStore;
import top.yealike.pep.control.agents.store.SkillStore;
import top.yealike.pep.control.support.AiExceptions;

class SkillServiceDeleteTest {

    private final SkillStore store = mock(SkillStore.class);
    private final SkillService service =
            new SkillService(store, mock(SkillValidator.class), mock(PublicationStore.class));

    @Test
    void deletesDraftFilesBeforeComponent() {
        UUID id = UUID.randomUUID();
        when(store.lock(id)).thenReturn(Optional.of(component(id)));
        when(store.bindingReferences(id)).thenReturn(0L);
        when(store.releases(id)).thenReturn(List.of());

        service.delete(id);

        var order = inOrder(store);
        order.verify(store).deleteDraftFiles(id);
        order.verify(store).delete(id);
    }

    @Test
    void refusesToDeleteComponentWithReleases() {
        UUID id = UUID.randomUUID();
        when(store.lock(id)).thenReturn(Optional.of(component(id)));
        when(store.bindingReferences(id)).thenReturn(0L);
        when(store.releases(id)).thenReturn(List.of(mock(SkillRelease.class)));

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(AiExceptions.AiException.class)
                .hasMessageContaining("已有发布版本");

        verify(store, never()).deleteDraftFiles(id);
        verify(store, never()).delete(id);
    }

    private static SkillComponent component(UUID id) {
        return new SkillComponent(
                id, "demo-skill", "Demo Skill", "", 1L, null, 0L, true, 1L, "admin", Instant.now(), Instant.now());
    }
}
