package top.yealike.pep.control.agents.bootstrap;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import top.yealike.pep.control.config.AiProperties;
import top.yealike.pep.control.access.UserIdentity;
import top.yealike.pep.control.agents.AgentService;
import top.yealike.pep.control.agents.config.PlatformProperties;
import top.yealike.pep.control.agents.credential.CredentialService;
import top.yealike.pep.control.agents.domain.PlatformRecords.AgentComponent;
import top.yealike.pep.control.agents.domain.PlatformRecords.AgentRelease;
import top.yealike.pep.control.agents.domain.PlatformRecords.Credential;
import top.yealike.pep.control.agents.domain.PlatformRecords.McpComponent;
import top.yealike.pep.control.agents.domain.PlatformRecords.McpRelease;
import top.yealike.pep.control.agents.domain.PlatformRecords.ModelProfile;
import top.yealike.pep.control.agents.domain.PlatformRecords.Publication;
import top.yealike.pep.control.agents.domain.PlatformRecords.SkillComponent;
import top.yealike.pep.control.agents.domain.PlatformRecords.SkillRelease;
import top.yealike.pep.control.agents.mcp.McpService;
import top.yealike.pep.control.agents.model.ModelProfileService;
import top.yealike.pep.control.agents.skill.SkillService;
import top.yealike.pep.control.agents.store.AgentStore.McpBindingInput;
import top.yealike.pep.control.agents.store.AgentStore.SkillBindingInput;
import top.yealike.pep.control.agents.store.PublicationStore;

/**
 * 演示环境幂等引导：补凭据、发布 MCP/Skill、绑定并发布 Agent。
 *
 * <p>默认关闭。引导失败只记录日志，不回滚、不阻塞管理面；管理员可以在页面上查看草稿与发布记录后手动重试。
 * 所有步骤都先检查现有状态，重复启动不会覆盖已发布内容。
 */
@Component
public class PlatformBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PlatformBootstrap.class);
    private static final UserIdentity SYSTEM = new UserIdentity("system", "system", List.of("ADMIN"), List.of("*"), 1);
    private static final Duration PUBLICATION_TIMEOUT = Duration.ofSeconds(90);

    private final PlatformProperties properties;
    private final AiProperties aiProperties;
    private final CredentialService credentials;
    private final ModelProfileService models;
    private final McpService mcps;
    private final SkillService skills;
    private final AgentService agents;
    private final PublicationStore publications;
    private final ExecutorService executor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            runnable -> {
                Thread thread = new Thread(runnable, "ai-platform-bootstrap");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    public PlatformBootstrap(
            PlatformProperties properties,
            AiProperties aiProperties,
            CredentialService credentials,
            ModelProfileService models,
            McpService mcps,
            SkillService skills,
            AgentService agents,
            PublicationStore publications) {
        this.properties = properties;
        this.aiProperties = aiProperties;
        this.credentials = credentials;
        this.models = models;
        this.mcps = mcps;
        this.skills = skills;
        this.agents = agents;
        this.publications = publications;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.bootstrap().enabled()) {
            return;
        }
        executor.submit(this::runSafely);
    }

    private void runSafely() {
        try {
            run();
        } catch (RuntimeException exception) {
            log.warn("AI platform bootstrap stopped: {}", safeMessage(exception));
        }
    }

    private void run() {
        PlatformProperties.Bootstrap config = properties.bootstrap();
        Credential credential = ensureCredential(config);
        if (credential == null) {
            log.warn("AI platform bootstrap skipped: 未提供模型密钥且不存在已有凭据");
            return;
        }
        ModelProfile model = ensureModel(config, credential);
        McpRelease mcp = ensureMcp(config);
        List<SkillRelease> skillReleases = ensureSkills(config);
        ensureAgent(model, mcp, skillReleases, config);
    }

    private Credential ensureCredential(PlatformProperties.Bootstrap config) {
        String key = config.modelCredentialKey();
        Credential existing = credentials.list().stream()
                .filter(item -> item.key().equals(key))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        String secret = config.modelApiKey();
        if (secret == null || secret.isBlank()) {
            return null;
        }
        return credentials.create(SYSTEM, key, "模型默认凭据", secret);
    }

    private ModelProfile ensureModel(PlatformProperties.Bootstrap config, Credential credential) {
        ModelProfile model = models.list().stream()
                .filter(item -> item.key().equals("deepseek-default"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少模型草稿 deepseek-default"));
        String baseUrl = blank(config.modelBaseUrl())
                ? model.baseUrl()
                : config.modelBaseUrl().strip();
        String modelName = blank(config.modelName())
                ? model.modelName()
                : config.modelName().strip();
        if (credential.id().equals(model.credentialId())
                && baseUrl.equals(model.baseUrl())
                && modelName.equals(model.modelName())) {
            return model;
        }
        return models.update(
                model.id(),
                model.revision(),
                model.displayName(),
                model.description(),
                baseUrl,
                modelName,
                credential.id(),
                model.options(),
                model.enabled());
    }

    private McpRelease ensureMcp(PlatformProperties.Bootstrap config) {
        McpComponent component = mcps.list().stream()
                .filter(item -> item.key().equals("ops"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少 MCP 草稿 ops"));
        Credential mcpCredential = ensureMcpCredential();
        if (mcpCredential != null) {
            component = bindMcpCredential(component, mcpCredential);
        }
        McpRelease active = activeMcp(component.id());
        if (active != null && (mcpCredential == null || active.credentialId() != null)) {
            approveConfiguredTools(component, config);
            return active;
        }
        if (!properties.registry().enabled()) {
            log.warn("AI platform bootstrap: 注册中心未启用，跳过 MCP ops 测试与发布");
            return active;
        }
        if (mcpCredential == null) {
            log.warn("AI platform bootstrap: 未配置 IPE_AI_MCP_TOKEN，OPS 工具将缺少调用上下文凭据");
        }
        Map<String, Object> report = mcps.test(component.id());
        Object reportId = report.get("reportId");
        if (!(reportId instanceof UUID id)) {
            throw new IllegalStateException("MCP 测试未返回报告编号");
        }
        approveConfiguredTools(component, config);
        String version = active == null ? "1.0.0" : nextPatchVersion(active.version());
        McpService.PublishResult published =
                mcps.publish(SYSTEM, component.id(), version, id, component.revision(), "bootstrap-mcp-ops-" + version);
        if (!awaitPublication(published.publicationId())) {
            throw new IllegalStateException("MCP ops 发布未在时限内完成");
        }
        return activeMcp(component.id());
    }

    private void approveConfiguredTools(McpComponent component, PlatformProperties.Bootstrap config) {
        if (config.reviewedReadOnlyTools() == null
                || config.reviewedReadOnlyTools().isEmpty()) {
            log.warn(
                    "AI platform bootstrap: 未配置 ipe.ai.platform.bootstrap.reviewed-read-only-tools，MCP {} 没有自动批准的工具",
                    component.key());
            return;
        }
        java.util.Set<String> approved = mcps.toolPolicies(component.id()).stream()
                .filter(policy -> policy.executionCategory().readOnly())
                .map(policy -> policy.toolName())
                .collect(java.util.stream.Collectors.toSet());
        for (String toolName : config.reviewedReadOnlyTools()) {
            if (toolName == null || toolName.isBlank() || approved.contains(toolName.strip())) {
                continue;
            }
            mcps.reviewTool(SYSTEM, component.id(), toolName.strip(), "READ_ONLY");
            log.info("AI platform bootstrap approved read-only tool: mcp={} tool={}", component.key(), toolName);
        }
    }

    private Credential ensureMcpCredential() {
        String secret = aiProperties.mcp() == null ? null : aiProperties.mcp().token();
        if (secret == null || secret.isBlank()) {
            return null;
        }
        Credential existing = credentials.list().stream()
                .filter(item -> item.key().equals("mcp-ops"))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        return credentials.create(SYSTEM, "mcp-ops", "OPS MCP 调用凭据", secret);
    }

    private McpComponent bindMcpCredential(McpComponent component, Credential credential) {
        Object current = component.draft().get("credentialId");
        if (credential.id().toString().equals(String.valueOf(current))) {
            return component;
        }
        Map<String, Object> draft = new java.util.LinkedHashMap<>(component.draft());
        draft.put("credentialId", credential.id().toString());
        return mcps.update(
                component.id(),
                component.revision(),
                component.displayName(),
                component.description(),
                component.nativeRegistryRef(),
                draft,
                component.enabled());
    }

    private static String nextPatchVersion(String current) {
        if (current == null || !current.matches("\\d+\\.\\d+\\.\\d+")) {
            return "1.0.0";
        }
        String[] parts = current.split("\\.");
        return parts[0] + "." + parts[1] + "." + (Integer.parseInt(parts[2]) + 1);
    }

    private List<SkillRelease> ensureSkills(PlatformProperties.Bootstrap config) {
        String version =
                blank(config.skillVersion()) ? "1.0.0" : config.skillVersion().strip();
        return skills.list().stream()
                .filter(item ->
                        item.key().equals("protocol-analysis") || item.key().equals("device-offline-check"))
                .map(component -> ensureSkill(component, version))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private SkillRelease ensureSkill(SkillComponent component, String version) {
        skills.recomputeDraftDigests(component.id());
        SkillRelease active = skills.releases(component.id()).stream()
                .filter(item -> "ACTIVE".equals(item.state()))
                .findFirst()
                .orElse(null);
        if (active != null) {
            return active;
        }
        SkillComponent current = skills.require(component.id());
        SkillService.PublishResult published = skills.publish(
                SYSTEM,
                current.id(),
                version,
                current.revision(),
                "bootstrap-skill-" + component.key() + "-" + version);
        if (!awaitPublication(published.publicationId())) {
            throw new IllegalStateException("Skill " + component.key() + " 发布未在时限内完成");
        }
        return skills.releases(component.id()).stream()
                .filter(item -> item.version().equals(version) && "ACTIVE".equals(item.state()))
                .findFirst()
                .orElse(null);
    }

    private void ensureAgent(
            ModelProfile model, McpRelease mcp, List<SkillRelease> skillReleases, PlatformProperties.Bootstrap config) {
        AgentComponent agent = agents.list().stream()
                .filter(item -> item.key().equals("protocol-expert"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少 Agent 草稿 protocol-expert"));
        AgentRelease active = agentReleases(agent.id()).stream()
                .filter(item -> "ACTIVE".equals(item.state()))
                .findFirst()
                .orElse(null);
        if (active != null) {
            return;
        }
        List<McpBindingInput> mcpBindings = mcp == null
                ? List.of()
                : mcps.readOnlyTools(mcp).stream()
                        .limit(16)
                        .map(tool -> new McpBindingInput(mcp.id(), tool.name(), true, 0))
                        .toList();
        List<SkillBindingInput> skillBindings = new java.util.ArrayList<>();
        for (int index = 0; index < skillReleases.size(); index++) {
            SkillRelease release = skillReleases.get(index);
            skillBindings.add(new SkillBindingInput(release.id(), index, "AUTO"));
        }
        AgentComponent updated = agents.update(
                agent.id(),
                agent.revision(),
                agent.displayName(),
                agent.description(),
                model.id(),
                agent.systemPrompt(),
                agent.modelOptions(),
                agent.budget(),
                agent.enabled(),
                mcpBindings,
                skillBindings);
        String version =
                blank(config.skillVersion()) ? "1.0.0" : config.skillVersion().strip();
        AgentService.PublishResult published = agents.publish(
                SYSTEM, updated.id(), version, updated.revision(), "bootstrap-agent-protocol-expert-" + version);
        if (!awaitPublication(published.publicationId())) {
            throw new IllegalStateException("Agent protocol-expert 发布未在时限内完成");
        }
        log.info(
                "AI platform bootstrap published agent={} version={} tools={} skills={}",
                agent.key(),
                version,
                mcpBindings.size(),
                skillBindings.size());
    }

    private McpRelease activeMcp(UUID componentId) {
        return mcps.releases(componentId).stream()
                .filter(item -> "ACTIVE".equals(item.state()))
                .findFirst()
                .orElse(null);
    }

    private List<AgentRelease> agentReleases(UUID agentId) {
        return agents.releases(agentId);
    }

    private boolean awaitPublication(UUID publicationId) {
        Instant deadline = Instant.now().plus(PUBLICATION_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            Publication publication = publications.find(publicationId).orElse(null);
            if (publication != null) {
                if ("PUBLISHED".equals(publication.state())) {
                    return true;
                }
                if ("FAILED".equals(publication.state())) {
                    log.warn(
                            "AI platform bootstrap publication failed: id={} code={}",
                            publicationId,
                            publication.errorCode());
                    return false;
                }
            }
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }
}
