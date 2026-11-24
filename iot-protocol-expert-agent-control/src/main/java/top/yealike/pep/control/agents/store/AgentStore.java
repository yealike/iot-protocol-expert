package top.yealike.pep.control.agents.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import top.yealike.pep.control.agents.domain.AgentComponent;
import top.yealike.pep.control.agents.domain.AgentMcpBinding;
import top.yealike.pep.control.agents.domain.AgentRelease;
import top.yealike.pep.control.agents.domain.AgentSkillBinding;
import top.yealike.pep.control.agents.domain.AgentTeam;
import top.yealike.pep.control.agents.domain.AgentTeamSettings;
import top.yealike.pep.control.agents.domain.McpBindingInput;
import top.yealike.pep.control.agents.domain.SkillBindingInput;
import top.yealike.pep.control.agents.mapper.AgentPlatformMapper;
import top.yealike.pep.control.support.JsonSupport;
import top.yealike.pep.runtime.contract.A2aCapability;
import top.yealike.pep.runtime.contract.AgentManifest;
import top.yealike.pep.runtime.contract.DelegationPolicy;

@Repository
public class AgentStore {

    private final AgentPlatformMapper mapper;

    public AgentStore(AgentPlatformMapper mapper) {
        this.mapper = mapper;
    }

    public List<AgentComponent> list() {
        return mapper.list();
    }

    public Optional<AgentComponent> find(UUID id) {
        return mapper.find(id).stream().findFirst();
    }

    public Optional<AgentComponent> findByKey(String key) {
        return mapper.findByKey(key).stream().findFirst();
    }

    public Optional<AgentComponent> lock(UUID id) {
        return mapper.lock(id).stream().findFirst();
    }

    public UUID insert(
            String key,
            String displayName,
            String description,
            UUID modelProfileId,
            String systemPrompt,
            String modelOptionsJson,
            String budgetJson,
            String actor) {
        UUID id = UUID.randomUUID();
        mapper.insert(
                id, key, displayName, description, modelProfileId, systemPrompt, modelOptionsJson, budgetJson, actor);
        return id;
    }

    public int update(
            UUID id,
            long expectedRevision,
            String displayName,
            String description,
            UUID modelProfileId,
            String systemPrompt,
            String modelOptionsJson,
            String budgetJson,
            boolean enabled) {
        return mapper.update(
                id,
                expectedRevision,
                displayName,
                description,
                modelProfileId,
                systemPrompt,
                modelOptionsJson,
                budgetJson,
                enabled);
    }

    public int setEnabled(UUID id, boolean enabled) {
        return mapper.setEnabled(id, enabled);
    }

    public int delete(UUID id) {
        mapper.deleteDelegateBindings(id);
        return mapper.delete(id);
    }

    /** 加载当前草稿的委派配置。 */
    public AgentTeam team(UUID agentId) {
        AgentTeamSettings settings = mapper.teamSettings(agentId);
        if (settings == null) {
            return AgentTeam.EMPTY;
        }
        return new AgentTeam(
                JsonSupport.readStringList(settings.tagsJson()),
                JsonSupport.readList(settings.capabilitiesJson(), A2aCapability.class),
                mapper.delegateBindings(agentId),
                JsonSupport.read(settings.policyJson(), DelegationPolicy.class));
    }

    /** 在上层事务内替换指定修订的委派配置。 */
    public boolean replaceTeam(UUID agentId, long revision, AgentTeam team) {
        AgentTeamSettings settings = new AgentTeamSettings(
                JsonSupport.write(team.capabilityTags()),
                JsonSupport.write(team.a2aCapabilities()),
                team.delegationPolicy() == null ? null : JsonSupport.write(team.delegationPolicy()));
        if (mapper.updateTeamSettings(agentId, revision, settings) == 0) {
            return false;
        }
        mapper.deleteDelegateBindings(agentId);
        if (!team.delegates().isEmpty()
                && mapper.insertDelegateBindings(agentId, team.delegates())
                        != team.delegates().size()) {
            throw top.yealike.pep.control.support.AiExceptions.conflict("TARGET_RELEASE_UNAVAILABLE", "委派目标版本已变化");
        }
        return true;
    }

    public List<AgentMcpBinding> mcpBindings(UUID agentId) {
        return mapper.mcpBindings(agentId);
    }

    public List<AgentSkillBinding> skillBindings(UUID agentId) {
        return mapper.skillBindings(agentId);
    }

    public void replaceBindings(UUID agentId, List<McpBindingInput> mcp, List<SkillBindingInput> skills) {
        mapper.deleteMcpBindings(agentId);
        mapper.deleteSkillBindings(agentId);
        insertMcpBindings(agentId, mcp);
        insertSkillBindings(agentId, skills);
    }

    public void replaceMcpBindings(UUID agentId, List<McpBindingInput> mcp) {
        mapper.deleteMcpBindings(agentId);
        insertMcpBindings(agentId, mcp);
    }

    public void replaceSkillBindings(UUID agentId, List<SkillBindingInput> skills) {
        mapper.deleteSkillBindings(agentId);
        insertSkillBindings(agentId, skills);
    }

    public UUID insertRelease(
            UUID agentId, String version, AgentManifest manifest, String digest, long generation, String actor) {
        UUID id = UUID.randomUUID();
        mapper.insertRelease(id, agentId, version, JsonSupport.write(manifest), digest, generation, actor);
        return id;
    }

    public Optional<AgentRelease> findRelease(UUID id) {
        return mapper.findReleaseById(id).stream().findFirst();
    }

    public Optional<AgentRelease> findRelease(String key, String version) {
        return mapper.findReleaseByKeyAndVersion(key, version).stream().findFirst();
    }

    public Optional<AgentRelease> latestActive(UUID agentId) {
        return mapper.latestActive(agentId).stream().findFirst();
    }

    public List<AgentRelease> releases(UUID agentId) {
        return mapper.releases(agentId);
    }

    public List<AgentRelease> activeReleases() {
        return mapper.activeReleases();
    }

    public List<AgentRelease> defaultReleases() {
        return mapper.defaultReleases();
    }

    public List<AgentRelease> desiredReleases() {
        return mapper.desiredReleases();
    }

    public Optional<AgentRelease> findPublishedRelease(String agentKey) {
        return mapper.findPublishedRelease(agentKey).stream().findFirst();
    }

    public long nextGeneration(UUID agentId) {
        Long value = mapper.nextGeneration(agentId);
        return value == null ? 1 : value;
    }

    public long releaseCount(UUID agentId) {
        Long value = mapper.releaseCount(agentId);
        return value == null ? 0 : value;
    }

    public long conversationReferenceCount(UUID agentId) {
        Long value = mapper.conversationReferenceCount(agentId);
        return value == null ? 0 : value;
    }

    public int setReleaseState(UUID releaseId, String state) {
        return mapper.setReleaseState(releaseId, state);
    }

    public boolean publishComponent(UUID agentId, String version, long expectedGeneration) {
        return mapper.publishComponent(agentId, version, expectedGeneration) > 0;
    }

    public Optional<String> publishedVersion(String agentKey) {
        return Optional.ofNullable(mapper.publishedVersion(agentKey)).filter(value -> !value.isBlank());
    }

    private void insertMcpBindings(UUID agentId, List<McpBindingInput> bindings) {
        for (McpBindingInput binding : bindings) {
            mapper.insertMcpBinding(
                    agentId, binding.mcpReleaseId(), binding.toolName(), binding.required(), binding.executionOrder());
        }
    }

    private void insertSkillBindings(UUID agentId, List<SkillBindingInput> bindings) {
        for (SkillBindingInput binding : bindings) {
            mapper.insertSkillBinding(agentId, binding.skillReleaseId(), binding.order(), binding.loadMode());
        }
    }
}
