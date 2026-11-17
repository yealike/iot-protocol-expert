package top.yealike.pep.control.agents.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import top.yealike.pep.runtime.contract.SkillBundle;
import top.yealike.pep.control.agents.mapper.SkillPlatformMapper;
import top.yealike.pep.control.agents.domain.PlatformRecords.SkillComponent;
import top.yealike.pep.control.agents.domain.PlatformRecords.SkillDraftFile;
import top.yealike.pep.control.agents.domain.PlatformRecords.SkillRelease;
import top.yealike.pep.control.support.JsonSupport;

@Repository
public class SkillStore {

    private final SkillPlatformMapper mapper;

    public SkillStore(SkillPlatformMapper mapper) {
        this.mapper = mapper;
    }

    public List<SkillComponent> list() {
        return mapper.listComponents();
    }

    public Optional<SkillComponent> find(UUID id) {
        return mapper.findComponent(id).stream().findFirst();
    }

    public Optional<SkillComponent> findByKey(String key) {
        return mapper.findComponentByKey(key).stream().findFirst();
    }

    public Optional<SkillComponent> lock(UUID id) {
        return mapper.lockComponent(id).stream().findFirst();
    }

    public UUID insert(String key, String displayName, String description, String actor) {
        UUID id = UUID.randomUUID();
        mapper.insertComponent(id, key, displayName, description, actor);
        return id;
    }

    public int update(UUID id, long expectedRevision, String displayName, String description, boolean enabled) {
        return mapper.updateComponent(id, expectedRevision, displayName, description, enabled);
    }

    public int setEnabled(UUID id, boolean enabled) {
        return mapper.setComponentEnabled(id, enabled);
    }

    public int bumpRevision(UUID id, long expectedRevision) {
        return mapper.bumpRevision(id, expectedRevision);
    }

    public int delete(UUID id) {
        return mapper.deleteComponent(id);
    }

    public int deleteDraftFiles(UUID id) {
        return mapper.deleteDraftFiles(id);
    }

    public long bindingReferences(UUID skillId) {
        return mapper.bindingReferences(skillId);
    }

    public List<SkillDraftFile> files(UUID skillId) {
        return mapper.files(skillId);
    }

    public Optional<SkillDraftFile> file(UUID skillId, String path) {
        return mapper.file(skillId, path).stream().findFirst();
    }

    public void upsertFile(UUID skillId, String path, String content, String digest) {
        mapper.upsertFile(UUID.randomUUID(), skillId, path, content, digest);
        mapper.bumpDraftRevision(skillId);
    }

    public void deleteFile(UUID skillId, String path) {
        mapper.deleteFile(skillId, path);
        mapper.bumpDraftRevision(skillId);
    }

    public UUID insertRelease(
            UUID skillId,
            String version,
            long generation,
            String name,
            String description,
            SkillBundle bundle,
            String digest,
            String actor) {
        UUID id = UUID.randomUUID();
        mapper.insertRelease(
                id, skillId, version, generation, name, description, JsonSupport.write(bundle), digest, actor);
        return id;
    }

    public Optional<SkillRelease> findRelease(UUID id) {
        return mapper.findRelease(id).stream().findFirst();
    }

    public Optional<SkillRelease> findRelease(String key, String version) {
        return mapper.findReleaseByKey(key, version).stream().findFirst();
    }

    public List<SkillRelease> releases(UUID skillId) {
        return mapper.releases(skillId);
    }

    public int setReleaseState(UUID releaseId, String state) {
        return mapper.setReleaseState(releaseId, state);
    }

    public boolean publishComponent(UUID skillId, String version, long expectedGeneration) {
        int updated = mapper.publishComponent(skillId, version, expectedGeneration);
        return updated > 0 || mapper.countPublished(skillId, version, expectedGeneration) > 0;
    }
}
