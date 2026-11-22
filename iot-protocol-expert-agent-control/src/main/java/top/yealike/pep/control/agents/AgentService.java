package top.yealike.pep.control.agents;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.pep.control.access.policy.PolicyService;
import top.yealike.pep.control.agents.domain.PlatformRecords.AgentCatalogItem;
import top.yealike.pep.control.agents.domain.PlatformRecords.AgentComponent;
import top.yealike.pep.control.agents.domain.PlatformRecords.AgentMcpBinding;
import top.yealike.pep.control.agents.domain.PlatformRecords.AgentRelease;
import top.yealike.pep.control.agents.domain.PlatformRecords.AgentSkillBinding;
import top.yealike.pep.control.agents.domain.PlatformRecords.CompileResult;
import top.yealike.pep.control.agents.domain.PlatformRecords.McpRelease;
import top.yealike.pep.control.agents.domain.PlatformRecords.Publication;
import top.yealike.pep.control.agents.domain.PlatformRecords.SkillRelease;
import top.yealike.pep.control.agents.store.AgentStore;
import top.yealike.pep.control.agents.store.AgentStore.McpBindingInput;
import top.yealike.pep.control.agents.store.AgentStore.SkillBindingInput;
import top.yealike.pep.control.agents.store.McpStore;
import top.yealike.pep.control.agents.store.PublicationStore;
import top.yealike.pep.control.agents.store.SkillStore;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.control.support.JsonSupport;
import top.yealike.pep.runtime.contract.ErrorCodes;
import top.yealike.pep.runtime.contract.UserIdentity;

/** Agent 草稿、绑定、编译、发布与目录。发布只生成不可变 release 与异步 publication。 */
@Service
public class AgentService {

    private final AgentStore agents;
    private final McpStore mcps;
    private final SkillStore skills;
    private final AgentCompiler compiler;
    private final PublicationStore publications;
    private final PolicyService policy;

    public AgentService(
            AgentStore agents,
            McpStore mcps,
            SkillStore skills,
            AgentCompiler compiler,
            PublicationStore publications,
            PolicyService policy) {
        this.agents = agents;
        this.mcps = mcps;
        this.skills = skills;
        this.compiler = compiler;
        this.publications = publications;
        this.policy = policy;
    }

    public List<AgentComponent> list() {
        return agents.list();
    }

    public AgentComponent require(UUID id) {
        return agents.find(id).orElseThrow(() -> AiExceptions.notFound("Agent 不存在"));
    }

    public AgentDetail detail(UUID id) {
        AgentComponent component = require(id);
        return new AgentDetail(component, agents.mcpBindings(id), agents.skillBindings(id), agents.releases(id));
    }

    @Transactional
    public AgentComponent create(
            UserIdentity actor,
            String key,
            String displayName,
            String description,
            UUID modelProfileId,
            String systemPrompt,
            Map<String, Object> modelOptions,
            Map<String, Object> budget,
            List<McpBindingInput> mcpBindings,
            List<SkillBindingInput> skillBindings) {
        requireKey(key);
        if (agents.findByKey(key).isPresent()) {
            throw AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "Agent key 已存在");
        }
        validateBindingReferences(mcpBindings, skillBindings);
        UUID id = agents.insert(
                key,
                displayName,
                description,
                modelProfileId,
                systemPrompt == null ? "" : systemPrompt,
                JsonSupport.write(modelOptions == null ? Map.of() : modelOptions),
                JsonSupport.write(budget == null ? Map.of() : budget),
                actor.username());
        agents.replaceBindings(
                id, mcpBindings == null ? List.of() : mcpBindings, skillBindings == null ? List.of() : skillBindings);
        return require(id);
    }

    @Transactional
    public AgentComponent update(
            UUID id,
            long expectedRevision,
            String displayName,
            String description,
            UUID modelProfileId,
            String systemPrompt,
            Map<String, Object> modelOptions,
            Map<String, Object> budget,
            boolean enabled,
            List<McpBindingInput> mcpBindings,
            List<SkillBindingInput> skillBindings) {
        require(id);
        if (mcpBindings != null || skillBindings != null) {
            validateBindingReferences(
                    mcpBindings == null ? List.of() : mcpBindings, skillBindings == null ? List.of() : skillBindings);
        }
        int updated = agents.update(
                id,
                expectedRevision,
                displayName,
                description,
                modelProfileId,
                systemPrompt == null ? "" : systemPrompt,
                JsonSupport.write(modelOptions == null ? Map.of() : modelOptions),
                JsonSupport.write(budget == null ? Map.of() : budget),
                enabled);
        if (updated == 0) {
            throw AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "Agent 已被其他请求修改，请刷新后重试");
        }
        if (mcpBindings != null && skillBindings != null) {
            agents.replaceBindings(id, mcpBindings, skillBindings);
        } else if (mcpBindings != null) {
            agents.replaceMcpBindings(id, mcpBindings);
        } else if (skillBindings != null) {
            agents.replaceSkillBindings(id, skillBindings);
        }
        return require(id);
    }

    public CompileResult validate(UUID id) {
        AgentComponent component = require(id);
        String version = nextVersion(component.publishedVersion());
        return compiler.compile(component, version);
    }

    @Transactional
    public PublishResult publish(
            UserIdentity actor, UUID id, String version, long expectedRevision, String idempotencyKey) {
        AgentComponent component = agents.lock(id).orElseThrow(() -> AiExceptions.notFound("Agent 不存在"));
        String key = blank(idempotencyKey) ? UUID.randomUUID().toString() : idempotencyKey.strip();
        var existing = publications.findByIdempotency("AGENT", id, key);
        if (existing.isPresent()) {
            Publication publication = existing.get();
            if (!blank(version) && !publication.version().equals(requireVersion(version))) {
                throw AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "幂等键已用于不同的 Agent 发布请求");
            }
            AgentRelease release = agents.releases(id).stream()
                    .filter(item -> item.version().equals(publication.version()))
                    .findFirst()
                    .orElseThrow(() -> AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "发布记录与版本不一致"));
            return new PublishResult(release.id(), publication.id(), release.version(), release.manifestDigest());
        }
        if (component.revision() != expectedRevision) {
            throw AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "Agent 已被其他请求修改，请刷新后重试");
        }
        String resolvedVersion = blank(version) ? nextVersion(component.publishedVersion()) : requireVersion(version);
        if (agents.findRelease(component.key(), resolvedVersion).isPresent()) {
            throw AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "该 Agent 版本已存在，发布内容不可覆盖");
        }
        CompileResult result = compiler.compile(component, resolvedVersion);
        if (!result.valid()) {
            throw AiExceptions.unprocessable(
                    "AGENT_COMPILE_FAILED",
                    result.errors().isEmpty()
                            ? "Agent 编译失败"
                            : result.errors().getFirst().message());
        }
        long generation = Math.max(agents.nextGeneration(id), publications.nextGeneration("AGENT", id));
        UUID releaseId = agents.insertRelease(
                id, resolvedVersion, result.manifest(), result.manifestDigest(), generation, actor.username());
        UUID publicationId = publications.insert(
                "AGENT",
                id,
                resolvedVersion,
                component.revision(),
                generation,
                key,
                Instant.now().plusSeconds(60));
        publications.insertOutbox(
                "AGENT",
                releaseId,
                generation,
                "PUBLISH_REQUESTED",
                JsonSupport.write(
                        Map.of("releaseId", releaseId, "publicationId", publicationId, "version", resolvedVersion)));
        return new PublishResult(releaseId, publicationId, resolvedVersion, result.manifestDigest());
    }

    @Transactional
    public RollbackResult rollback(UUID id, long expectedRevision, String targetVersion, String idempotencyKey) {
        AgentComponent component = agents.lock(id).orElseThrow(() -> AiExceptions.notFound("Agent 不存在"));
        String version = requireVersion(targetVersion);
        String requestedKey = blank(idempotencyKey) ? UUID.randomUUID().toString() : idempotencyKey.strip();
        String key = requestedKey.startsWith("rollback-") ? requestedKey : "rollback-" + requestedKey;
        if (key.length() > 120) {
            throw AiExceptions.badRequest("幂等键过长");
        }
        var existing = publications.findByIdempotency("AGENT", id, key);
        if (existing.isPresent()) {
            Publication publication = existing.get();
            if (!publication.version().equals(version)) {
                throw AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "幂等键已用于不同的回滚目标版本");
            }
            AgentRelease replayTarget = agents.findRelease(component.key(), version)
                    .orElseThrow(() -> AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "回滚记录与版本不一致"));
            return new RollbackResult(
                    replayTarget.id(),
                    publication.id(),
                    publication.version(),
                    publication.generation(),
                    publication.state());
        }
        if (component.revision() != expectedRevision) {
            throw AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "Agent 已被其他请求修改，请刷新后重试");
        }
        AgentRelease target =
                agents.findRelease(component.key(), version).orElseThrow(() -> AiExceptions.notFound("目标版本不存在"));
        if ("REVOKED".equals(target.state()) || "FAILED".equals(target.state())) {
            throw AiExceptions.conflict(ErrorCodes.RELEASE_NOT_ACTIVE, "目标版本已废止或发布失败，不能回滚");
        }
        long generation = Math.max(
                component.publicationGeneration() + 1,
                Math.max(agents.nextGeneration(id), publications.nextGeneration("AGENT", id)));
        UUID publicationId = publications.insert(
                "AGENT",
                id,
                target.version(),
                component.revision(),
                generation,
                key,
                Instant.now().plusSeconds(60));
        publications.insertOutbox(
                "AGENT",
                target.id(),
                generation,
                "ROLLBACK_REQUESTED",
                JsonSupport.write(Map.of(
                        "releaseId", target.id(),
                        "publicationId", publicationId,
                        "version", target.version())));
        return new RollbackResult(target.id(), publicationId, target.version(), generation, "VALIDATING");
    }

    @Transactional
    public void delete(UUID id) {
        AgentComponent component = agents.lock(id).orElseThrow(() -> AiExceptions.notFound("Agent 不存在"));
        if (agents.releaseCount(id) > 0) {
            throw AiExceptions.conflict(ErrorCodes.COMPONENT_IN_USE, "Agent 已有发布版本，请停用而不是删除");
        }
        if (agents.conversationReferenceCount(id) > 0) {
            throw AiExceptions.conflict(ErrorCodes.COMPONENT_IN_USE, "Agent 已被会话引用，不能删除");
        }
        agents.replaceBindings(id, List.of(), List.of());
        agents.delete(id);
    }

    public List<AgentCatalogItem> catalog(UserIdentity identity) {
        Map<String, AgentCatalogItem> result = new LinkedHashMap<>();
        for (AgentRelease release : agents.defaultReleases()) {
            if (!policy.canUseAgent(identity, release.agentKey()).allowed()) {
                continue;
            }
            var manifest = release.manifest();
            int toolCount = manifest.mcp().stream()
                    .mapToInt(item -> item.tools().size())
                    .sum();
            result.putIfAbsent(
                    release.agentKey(),
                    new AgentCatalogItem(
                            release.agentKey(),
                            release.version(),
                            manifest.displayName(),
                            "",
                            "ACTIVE",
                            toolCount,
                            manifest.skills().size(),
                            manifest.skills().stream()
                                    .map(top.yealike.pep.runtime.contract.AgentManifest.SkillBinding::key)
                                    .toList(),
                            true,
                            false));
        }
        return new ArrayList<>(result.values());
    }

    public AgentRelease requirePublishedRelease(String agentKey, String version) {
        AgentRelease release = blank(version)
                ? agents.findPublishedRelease(agentKey).orElse(null)
                : agents.findRelease(agentKey, requireVersion(version)).orElse(null);
        if (release == null) {
            throw AiExceptions.notFound("Agent 没有可用发布版本：" + agentKey);
        }
        if (!"ACTIVE".equals(release.state())) {
            throw AiExceptions.conflict(ErrorCodes.RELEASE_NOT_ACTIVE, "Agent 版本未激活：" + agentKey);
        }
        return release;
    }

    public AgentRelease release(UUID releaseId) {
        return agents.findRelease(releaseId).orElseThrow(() -> AiExceptions.notFound("Agent 发布版本不存在"));
    }

    public AgentRelease release(String agentKey, String version) {
        return agents.findRelease(agentKey, version).orElseThrow(() -> AiExceptions.notFound("Agent 发布版本不存在"));
    }

    public List<AgentRelease> releases(UUID agentId) {
        return agents.releases(agentId);
    }

    private void validateBindingReferences(List<McpBindingInput> mcpBindings, List<SkillBindingInput> skillBindings) {
        for (McpBindingInput binding : mcpBindings) {
            if (binding == null || binding.mcpReleaseId() == null || blank(binding.toolName())) {
                throw AiExceptions.badRequest("MCP 绑定缺少版本或工具名");
            }
            McpRelease release = mcps.findRelease(binding.mcpReleaseId())
                    .orElseThrow(() -> AiExceptions.badRequest("MCP 版本不存在：" + binding.mcpReleaseId()));
            if (!"ACTIVE".equals(release.state())) {
                throw AiExceptions.badRequest("MCP 版本未发布，不能绑定：" + release.componentKey());
            }
            if (release.tools().stream().noneMatch(tool -> tool.name().equals(binding.toolName()))) {
                throw AiExceptions.badRequest("MCP 版本没有该工具：" + binding.toolName());
            }
            if (!mcps.toolCategories(release.componentId())
                    .getOrDefault(binding.toolName(), top.yealike.pep.runtime.contract.McpToolExecutionCategory.UNKNOWN)
                    .readOnly()) {
                throw AiExceptions.forbidden(
                        ErrorCodes.TOOL_DENIED,
                        "一期仅允许绑定平台审核通过的只读工具：" + release.componentKey() + "." + binding.toolName());
            }
        }
        for (SkillBindingInput binding : skillBindings) {
            if (binding == null || binding.skillReleaseId() == null) {
                throw AiExceptions.badRequest("Skill 绑定缺少版本");
            }
            SkillRelease release = skills.findRelease(binding.skillReleaseId())
                    .orElseThrow(() -> AiExceptions.badRequest("Skill 版本不存在：" + binding.skillReleaseId()));
            if (!"ACTIVE".equals(release.state())) {
                throw AiExceptions.badRequest("Skill 版本未发布，不能绑定：" + release.componentKey());
            }
            String mode =
                    binding.loadMode() == null ? "AUTO" : binding.loadMode().toUpperCase(Locale.ROOT);
            if (!"AUTO".equals(mode) && !"ALWAYS".equals(mode)) {
                throw AiExceptions.badRequest("Skill 加载方式只支持 AUTO 或 ALWAYS");
            }
        }
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[a-z0-9][a-z0-9-]{1,39}")) {
            throw AiExceptions.badRequest("Agent key 只允许 2-40 位小写字母、数字和横线");
        }
    }

    private static String requireVersion(String version) {
        if (version == null || !version.matches("\\d+\\.\\d+\\.\\d+")) {
            throw AiExceptions.badRequest("版本必须为 x.y.z");
        }
        return version;
    }

    private static String nextVersion(String current) {
        if (current == null || !current.matches("\\d+\\.\\d+\\.\\d+")) {
            return "1.0.0";
        }
        String[] parts = current.split("\\.");
        return parts[0] + "." + parts[1] + "." + (Integer.parseInt(parts[2]) + 1);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record AgentDetail(
            AgentComponent agent,
            List<AgentMcpBinding> mcpBindings,
            List<AgentSkillBinding> skillBindings,
            List<AgentRelease> releases) {}

    public record PublishResult(UUID releaseId, UUID publicationId, String version, String manifestDigest) {}

    public record RollbackResult(UUID releaseId, UUID publicationId, String version, long generation, String state) {}
}
