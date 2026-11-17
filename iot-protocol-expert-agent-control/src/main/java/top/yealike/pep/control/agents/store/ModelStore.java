package top.yealike.pep.control.agents.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import top.yealike.pep.control.agents.domain.PlatformRecords.ModelProfile;
import top.yealike.pep.control.agents.mapper.ModelProfileMapper;

@Repository
public class ModelStore {

    private final ModelProfileMapper mapper;

    public ModelStore(ModelProfileMapper mapper) {
        this.mapper = mapper;
    }

    public List<ModelProfile> list() {
        return mapper.list();
    }

    public Optional<ModelProfile> find(UUID id) {
        return mapper.find(id).stream().findFirst();
    }

    public Optional<ModelProfile> findByKey(String key) {
        return mapper.findByKey(key).stream().findFirst();
    }

    public UUID insert(
            String key,
            String displayName,
            String description,
            String providerType,
            String baseUrl,
            String modelName,
            UUID credentialId,
            String optionsJson,
            String actor) {
        UUID id = UUID.randomUUID();
        mapper.insert(
                id, key, displayName, description, providerType, baseUrl, modelName, credentialId, optionsJson, actor);
        return id;
    }

    public int update(
            UUID id,
            long expectedRevision,
            String displayName,
            String description,
            String baseUrl,
            String modelName,
            UUID credentialId,
            String optionsJson,
            boolean enabled) {
        return mapper.update(
                id, expectedRevision, displayName, description, baseUrl, modelName, credentialId, optionsJson, enabled);
    }

    public int setEnabled(UUID id, boolean enabled) {
        return mapper.setEnabled(id, enabled);
    }
}
