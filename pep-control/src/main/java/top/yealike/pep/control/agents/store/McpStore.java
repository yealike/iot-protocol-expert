package top.yealike.pep.control.agents.store;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import top.yealike.pep.runtime.contract.McpToolDescriptor;
import top.yealike.pep.runtime.contract.McpToolExecutionCategory;
import top.yealike.pep.control.agents.mapper.McpPlatformMapper;
import top.yealike.pep.control.agents.domain.PlatformRecords.McpComponent;
import top.yealike.pep.control.agents.domain.PlatformRecords.McpRelease;
import top.yealike.pep.control.agents.domain.PlatformRecords.McpTestReport;
import top.yealike.pep.control.agents.domain.PlatformRecords.McpToolPolicy;
import top.yealike.pep.control.support.JsonSupport;

@Repository
public class McpStore {

    private final McpPlatformMapper mapper;

    public McpStore(McpPlatformMapper mapper) {
        this.mapper = mapper;
    }

    public List<McpComponent> list() {
        return mapper.listComponents();
    }

    public Optional<McpComponent> find(UUID id) {
        return mapper.findComponent(id).stream().findFirst();
    }

    public Optional<McpComponent> findByKey(String key) {
        return mapper.findComponentByKey(key).stream().findFirst();
    }

    public Optional<McpComponent> lock(UUID id) {
        return mapper.lockComponent(id).stream().findFirst();
    }

    public UUID insert(
            String key,
            String displayName,
            String description,
            String origin,
            String nativeRegistryRefJson,
            String draftJson,
            String actor) {
        UUID id = UUID.randomUUID();
        mapper.insertComponent(id, key, displayName, description, origin, nativeRegistryRefJson, draftJson, actor);
        return id;
    }

    public int update(
            UUID id,
            long expectedRevision,
            String displayName,
            String description,
            String nativeRegistryRefJson,
            String draftJson,
            boolean enabled) {
        return mapper.updateComponent(
                id, expectedRevision, displayName, description, nativeRegistryRefJson, draftJson, enabled);
    }

    public int setEnabled(UUID id, boolean enabled) {
        return mapper.setComponentEnabled(id, enabled);
    }

    public int delete(UUID id) {
        return mapper.deleteComponent(id);
    }

    public List<McpToolPolicy> toolPolicies(UUID mcpId) {
        return mapper.toolPolicies(mcpId);
    }

    public Map<String, McpToolExecutionCategory> toolCategories(UUID mcpId) {
        Map<String, McpToolExecutionCategory> result = new HashMap<>();
        for (McpToolPolicy policy : mapper.toolPolicies(mcpId)) {
            result.put(policy.toolName(), policy.executionCategory());
        }
        return Map.copyOf(result);
    }

    public void upsertToolPolicy(UUID mcpId, String toolName, McpToolExecutionCategory category, String reviewedBy) {
        mapper.upsertToolPolicy(mcpId, toolName, category.name(), reviewedBy);
    }

    public int deleteToolPolicies(UUID mcpId) {
        return mapper.deleteToolPolicies(mcpId);
    }

    public int deleteTestReports(UUID mcpId) {
        return mapper.deleteTestReports(mcpId);
    }

    public long bindingReferences(UUID componentId) {
        return mapper.bindingReferences(componentId);
    }

    public UUID insertRelease(
            UUID componentId,
            String version,
            long generation,
            String transport,
            String endpointPolicyJson,
            UUID credentialId,
            List<McpToolDescriptor> tools,
            String catalogDigest,
            String contentDigest,
            String actor) {
        UUID id = UUID.randomUUID();
        mapper.insertRelease(
                id,
                componentId,
                version,
                generation,
                transport,
                endpointPolicyJson,
                credentialId,
                JsonSupport.write(tools),
                catalogDigest,
                contentDigest,
                actor);
        return id;
    }

    public Optional<McpRelease> findRelease(UUID id) {
        return mapper.findRelease(id).stream().findFirst();
    }

    public Optional<McpRelease> findRelease(String key, String version) {
        return mapper.findReleaseByKey(key, version).stream().findFirst();
    }

    public Optional<McpRelease> latestActive(UUID componentId) {
        return mapper.latestActiveRelease(componentId).stream().findFirst();
    }

    public List<McpRelease> releases(UUID componentId) {
        return mapper.releases(componentId);
    }

    public int setReleaseState(UUID releaseId, String state) {
        return mapper.setReleaseState(releaseId, state);
    }

    public boolean publishComponent(UUID componentId, String version, long expectedGeneration) {
        int updated = mapper.publishComponent(componentId, version, expectedGeneration);
        return updated > 0 || mapper.countPublished(componentId, version, expectedGeneration) > 0;
    }

    public UUID insertTestReport(
            UUID mcpId, long draftRevision, String configDigest, String resultJson, boolean success) {
        UUID id = UUID.randomUUID();
        mapper.insertTestReport(id, mcpId, draftRevision, configDigest, resultJson, success);
        return id;
    }

    public Optional<McpTestReport> findTestReport(UUID reportId) {
        return mapper.findTestReport(reportId).stream().findFirst();
    }

    public Optional<McpTestReport> latestTestReport(UUID mcpId) {
        return mapper.latestTestReport(mcpId).stream().findFirst();
    }
}
