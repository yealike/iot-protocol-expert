package top.yealike.pep.control.agents.registry;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import top.yealike.pep.control.agents.domain.PlatformRecords.RegistryProjection;
import top.yealike.pep.control.agents.store.RegistryStore;

/** 注册投影：记录每个发布版本期望与实际可见状态，发布协调与诊断共用。 */
@Service
public class RegistryProjectionService {

    private final RegistryStore store;
    private final NacosRegistryService registry;

    public RegistryProjectionService(RegistryStore store, NacosRegistryService registry) {
        this.store = store;
        this.registry = registry;
    }

    public void ready(String resourceType, UUID releaseId, String serviceName, String providerId) {
        store.upsert(resourceType, releaseId, serviceName, providerId, "READY", "READY", null);
    }

    public void preparing(String resourceType, UUID releaseId, String serviceName, String providerId, String reason) {
        store.upsert(resourceType, releaseId, serviceName, providerId, "READY", "PREPARING", reason);
    }

    public void failed(String resourceType, UUID releaseId, String serviceName, String providerId, String reason) {
        store.upsert(resourceType, releaseId, serviceName, providerId, "READY", "FAILED", reason);
    }

    public void revoked(String resourceType, UUID releaseId, String serviceName, String providerId) {
        store.upsert(resourceType, releaseId, serviceName, providerId, "REVOKED", "REVOKED", null);
    }

    public void reconcile(String resourceType, UUID releaseId) {
        store.find(resourceType, releaseId).ifPresent(projection -> {
            boolean platformResource = "MCP".equals(resourceType) || "SKILL".equals(resourceType);
            boolean visible = platformResource || !registry.enabled() || registry.hasHealthy(projection.serviceName());
            store.markObserved(resourceType, releaseId, visible ? "READY" : "MISSING", visible ? null : "注册中心未发现健康实例");
        });
    }

    public List<RegistryProjection> list() {
        return store.list();
    }
}
