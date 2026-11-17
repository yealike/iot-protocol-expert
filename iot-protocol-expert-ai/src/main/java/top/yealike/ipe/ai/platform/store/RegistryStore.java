package top.yealike.ipe.ai.platform.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import top.yealike.ipe.ai.mapper.RegistryProjectionMapper;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.RegistryProjection;

@Repository
public class RegistryStore {

    private final RegistryProjectionMapper mapper;

    public RegistryStore(RegistryProjectionMapper mapper) {
        this.mapper = mapper;
    }

    public void upsert(
            String resourceType,
            UUID releaseId,
            String serviceName,
            String providerId,
            String desiredState,
            String observedState,
            String error) {
        mapper.upsert(
                UUID.randomUUID(),
                resourceType,
                releaseId,
                serviceName,
                providerId,
                desiredState,
                observedState,
                error);
    }

    public List<RegistryProjection> list() {
        return mapper.list();
    }

    public Optional<RegistryProjection> find(String resourceType, UUID releaseId) {
        return mapper.find(resourceType, releaseId).stream().findFirst();
    }

    public void markObserved(String resourceType, UUID releaseId, String observedState, String error) {
        mapper.markObserved(resourceType, releaseId, observedState, error);
    }
}
