package top.yealike.pep.control.agents.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.yealike.pep.control.access.UserIdentity;
import top.yealike.pep.control.agents.domain.PlatformRecords.SkillComponent;
import top.yealike.pep.control.agents.domain.PlatformRecords.SkillDraftFile;
import top.yealike.pep.control.agents.store.PublicationStore;
import top.yealike.pep.control.agents.store.SkillStore;
import top.yealike.pep.runtime.contract.Digests;

class SkillServicePublicationTest {

    private static final UserIdentity ACTOR = new UserIdentity("admin", "admin", List.of("ADMIN"), List.of("*"), 1);

    @Test
    void publicationGenerationComesFromMonotonicPublicationStore() {
        UUID skillId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        UUID publicationId = UUID.randomUUID();
        String content = "---\nname: test-skill\ndescription: test\n---\nbody\n";
        SkillComponent component = new SkillComponent(
                skillId, "test-skill", "测试 Skill", "", 0L, null, 3L, true, 5L, "admin", Instant.now(), Instant.now());
        SkillDraftFile file = new SkillDraftFile(
                UUID.randomUUID(), skillId, "SKILL.md", content, Digests.sha256Hex(content), Instant.now());

        SkillStore store = mock(SkillStore.class);
        SkillValidator validator = mock(SkillValidator.class);
        PublicationStore publications = mock(PublicationStore.class);
        when(store.lock(skillId)).thenReturn(Optional.of(component));
        when(store.find(skillId)).thenReturn(Optional.of(component));
        when(store.files(skillId)).thenReturn(List.of(file));
        when(publications.findByIdempotency("SKILL", skillId, "key")).thenReturn(Optional.empty());
        when(validator.validate(eq("test-skill"), any()))
                .thenReturn(new SkillValidator.Validation(
                        List.of(), List.of(), new SkillValidator.Metadata("test-skill", "test", Map.of())));
        when(store.findRelease("test-skill", "1.0.0")).thenReturn(Optional.empty());
        when(publications.nextGeneration("SKILL", skillId)).thenReturn(8L);
        when(store.insertRelease(
                        eq(skillId), eq("1.0.0"), anyLong(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(releaseId);
        when(publications.insert(eq("SKILL"), eq(skillId), eq("1.0.0"), eq(5L), anyLong(), eq("key"), any()))
                .thenReturn(publicationId);

        SkillService service = new SkillService(store, validator, publications);

        service.publish(ACTOR, skillId, "1.0.0", 5L, "key");

        ArgumentCaptor<Long> releaseGeneration = ArgumentCaptor.forClass(Long.class);
        verify(store)
                .insertRelease(
                        eq(skillId),
                        eq("1.0.0"),
                        releaseGeneration.capture(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString(),
                        anyString());
        ArgumentCaptor<Long> publicationGeneration = ArgumentCaptor.forClass(Long.class);
        verify(publications)
                .insert(
                        eq("SKILL"),
                        eq(skillId),
                        eq("1.0.0"),
                        eq(5L),
                        publicationGeneration.capture(),
                        eq("key"),
                        any());
        assertThat(releaseGeneration.getValue()).isEqualTo(8L);
        assertThat(publicationGeneration.getValue()).isEqualTo(8L);
    }
}
