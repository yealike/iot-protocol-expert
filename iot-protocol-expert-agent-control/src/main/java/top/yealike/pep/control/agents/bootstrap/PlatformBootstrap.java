package top.yealike.pep.control.agents.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
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
import top.yealike.pep.control.config.AiProperties;
import top.yealike.pep.control.config.ModelGatewayProperties;
import top.yealike.pep.control.knowledge.KnowledgeService;
import top.yealike.pep.control.support.JsonSupport;
import top.yealike.pep.runtime.contract.AgentManifest;
import top.yealike.pep.runtime.contract.McpToolDescriptor;
import top.yealike.pep.runtime.contract.UserIdentity;

/**
 * 演示环境幂等引导：补凭据、发布 MCP/Skill、绑定并发布 Agent。
 *
 * <p>默认关闭。启用时在应用就绪前同步执行，任何一步失败都会中止启动并返回非零退出码。
 * 所有步骤都先检查现有状态，重复启动不会覆盖已发布内容。
 */
@Component
public class PlatformBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformBootstrap.class);
    private static final UserIdentity SYSTEM =
            new UserIdentity("system", "system", List.of("PLATFORM_ADMIN"), List.of("*"), 1);
    private static final Duration PUBLICATION_TIMEOUT = Duration.ofSeconds(90);
    private static final String OPS_MCP_KEY = "ops";
    private static final String KNOWLEDGE_MCP_KEY = "control-knowledge";
    private static final List<String> OPS_TOOLS =
            List.of("resolve_device", "list_device_frames", "decode_device_frame");
    private static final List<String> KNOWLEDGE_TOOLS = List.of("search_protocol_knowledge");
    static final String PROTOCOL_EXPERT_PROMPT = """
            你是充电桩协议分析专家，负责结合设备事实、实际报文和已发布的协议文档回答运维问题。

            1. 任何设备问题都先调用 resolve_device。平台设备 SN 是 8 位数字，平台枪号是设备 SN 后追加两位枪号。
            2. 云快充桩号、OCPP chargePointId 等第三方设备号必须先通过 resolve_device 映射，不能直接当作平台设备 SN。
            3. 回答“设备是否上报过某帧”时调用 list_device_frames，并引用 frame_id、上报时间、方向和帧类型；没有实际报文时不得推断设备已上报。
            4. 解释具体帧或字段前调用 decode_device_frame，优先使用 list_device_frames 返回的 frame_id，也可解码用户提供的完整原始帧。
            5. 查找协议依据时必须使用 resolve_device 返回的 protocol_document_id 调用 search_protocol_knowledge；不得用代码枚举、相似协议或其他设备版本的资料替代当前设备绑定的文档。
            6. 回答中明确区分“协议定义”和“实际上报”。引用文档证据时展示章节标题和页码，并说明证据支持的结论范围。
            7. 证据不足时返回 EVIDENCE_NOT_FOUND，列出已查询设备和文档，以及缺少的报文或文档内容。不要猜测帧码含义。
            """;

    private final PlatformProperties properties;
    private final AiProperties aiProperties;
    private final CredentialService credentials;
    private final ModelProfileService models;
    private final McpService mcps;
    private final SkillService skills;
    private final AgentService agents;
    private final PublicationStore publications;
    private final ModelGatewayProperties gatewayProperties;
    private final KnowledgeService knowledge;

    public PlatformBootstrap(
            PlatformProperties properties,
            AiProperties aiProperties,
            CredentialService credentials,
            ModelProfileService models,
            McpService mcps,
            SkillService skills,
            AgentService agents,
            PublicationStore publications,
            ModelGatewayProperties gatewayProperties,
            KnowledgeService knowledge) {
        this.properties = properties;
        this.aiProperties = aiProperties;
        this.credentials = credentials;
        this.models = models;
        this.mcps = mcps;
        this.skills = skills;
        this.agents = agents;
        this.publications = publications;
        this.gatewayProperties = gatewayProperties;
        this.knowledge = knowledge;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.bootstrap().enabled()) {
            return;
        }
        bootstrap();
    }

    private void bootstrap() {
        PlatformProperties.Bootstrap config = properties.bootstrap();
        Credential mcpCredential = ensureMcpCredential();
        ModelProfile model = ensureModel(config);
        McpDefinition opsDefinition = new McpDefinition(
                OPS_MCP_KEY,
                "OPS 充电桩运维",
                "按设备身份、报文和协议文档查询充电桩运行事实。",
                firstNonBlank(
                        config.opsMcpEndpoint(),
                        aiProperties.mcp() == null ? null : aiProperties.mcp().url()),
                OPS_TOOLS);
        McpDefinition knowledgeDefinition = new McpDefinition(
                KNOWLEDGE_MCP_KEY, "协议知识检索", "按设备绑定的已发布协议文档检索章节证据。", config.knowledgeMcpEndpoint(), KNOWLEDGE_TOOLS);
        McpRelease opsMcp = ensureManagedMcp(opsDefinition, config.reviewedReadOnlyTools(), mcpCredential);
        McpRelease knowledgeMcp =
                ensureManagedMcp(knowledgeDefinition, config.knowledgeReviewedReadOnlyTools(), mcpCredential);
        List<SkillRelease> skillReleases = ensureSkills(config);
        ensureAgent(model, opsMcp, knowledgeMcp, skillReleases, config);
        ensureKnowledgeIndex();
    }

    private ModelProfile ensureModel(PlatformProperties.Bootstrap config) {
        ModelProfile model = models.list().stream()
                .filter(item -> item.key().equals("deepseek-default"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少模型草稿 deepseek-default"));
        String baseUrl = gatewayBaseUrl();
        String modelName = model.key();
        if (model.credentialId() == null && baseUrl.equals(model.baseUrl()) && modelName.equals(model.modelName())) {
            return model;
        }
        return models.update(
                model.id(),
                model.revision(),
                model.displayName(),
                model.description(),
                baseUrl,
                modelName,
                null,
                model.options(),
                model.enabled());
    }

    private String gatewayBaseUrl() {
        String base = gatewayProperties.getBaseUrl();
        if (blank(base)) {
            return "http://127.0.0.1:19085/v1";
        }
        String value = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return value.endsWith("/v1") ? value : value + "/v1";
    }

    private McpRelease ensureManagedMcp(McpDefinition definition, List<String> configuredTools, Credential credential) {
        if (blank(definition.endpoint())) {
            throw new IllegalStateException("AI platform bootstrap: MCP " + definition.key() + " 未配置 endpoint");
        }
        McpComponent component = ensureMcpComponent(definition, credential);
        McpRelease active = activeMcp(component.id());
        if (activeMcpMatches(definition, component, active, credential)) {
            approveReadOnlyTools(component, definition.tools(), configuredTools);
            log.info("AI platform bootstrap: MCP {} 复用 ACTIVE 版本 {}", definition.key(), active.version());
            return active;
        }

        Map<String, Object> report = mcps.test(component.id());
        requireReportedTools(definition, report);
        approveReadOnlyTools(component, definition.tools(), configuredTools);
        Object reportId = report.get("reportId");
        if (!(reportId instanceof UUID id)) {
            throw new IllegalStateException("MCP 测试未返回报告编号");
        }
        String version = nextUnusedPatchVersion(
                active == null ? "1.0.0" : active.version(),
                mcps.releases(component.id()).stream().map(McpRelease::version).collect(Collectors.toSet()));
        McpService.PublishResult published = mcps.publish(
                SYSTEM,
                component.id(),
                version,
                id,
                component.revision(),
                "bootstrap-mcp-" + definition.key() + "-" + version);
        if (!awaitPublication(published.publicationId())) {
            throw new IllegalStateException("MCP " + definition.key() + " 发布未在时限内完成");
        }
        McpRelease result = activeMcp(component.id());
        if (!activeMcpMatches(definition, component, result, credential)) {
            throw new IllegalStateException("MCP " + definition.key() + " 发布后版本或工具目录不符合预期");
        }
        log.info("AI platform bootstrap: MCP {} 已发布版本 {} tools={}", definition.key(), version, definition.tools());
        return result;
    }

    private McpComponent ensureMcpComponent(McpDefinition definition, Credential credential) {
        McpComponent component = mcps.list().stream()
                .filter(item -> item.key().equals(definition.key()))
                .findFirst()
                .orElse(null);
        if (component == null) {
            UUID id = mcps.create(
                    SYSTEM,
                    definition.key(),
                    definition.displayName(),
                    definition.description(),
                    "REMOTE",
                    Map.of(),
                    initialMcpDraft(definition, credential));
            component = mcps.require(id);
        }
        Map<String, Object> draft = new LinkedHashMap<>(component.draft());
        draft.put("endpoint", definition.endpoint());
        draft.put("contextMode", "OPS");
        draft.put("credentialId", credential.id().toString());
        if (draft.equals(component.draft()) && component.enabled()) {
            return component;
        }
        return mcps.update(
                component.id(),
                component.revision(),
                component.displayName(),
                component.description(),
                component.nativeRegistryRef(),
                draft,
                true);
    }

    private static Map<String, Object> initialMcpDraft(McpDefinition definition, Credential credential) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("endpoint", definition.endpoint());
        draft.put("contextMode", "OPS");
        draft.put("credentialId", credential.id().toString());
        draft.put("timeoutMs", 20000);
        return draft;
    }

    private boolean activeMcpMatches(
            McpDefinition definition, McpComponent component, McpRelease active, Credential credential) {
        if (active == null
                || !"ACTIVE".equals(active.state())
                || !definition.endpoint().equals(text(active.endpointPolicy().get("endpoint")))
                || !"OPS".equals(text(active.endpointPolicy().get("contextMode")))
                || !credential.id().equals(active.credentialId())
                || configuredTimeout(component) != configuredTimeout(active)) {
            return false;
        }
        Set<String> available =
                active.tools().stream().map(McpToolDescriptor::name).collect(Collectors.toSet());
        return available.containsAll(definition.tools());
    }

    private static int configuredTimeout(McpComponent component) {
        Object raw = component.draft().get("timeoutMs");
        if (raw instanceof Number number) {
            return Math.min(Math.max(number.intValue(), 1000), 20000);
        }
        return 20000;
    }

    private static int configuredTimeout(McpRelease release) {
        Object raw = release.endpointPolicy().get("timeoutMs");
        return raw instanceof Number number ? number.intValue() : 20000;
    }

    private void approveReadOnlyTools(
            McpComponent component, List<String> requiredTools, List<String> configuredTools) {
        LinkedHashSet<String> requested = new LinkedHashSet<>(requiredTools);
        if (configuredTools != null) {
            configuredTools.stream()
                    .filter(tool -> tool != null && !tool.isBlank())
                    .map(String::strip)
                    .forEach(requested::add);
        }
        Set<String> approved = mcps.toolPolicies(component.id()).stream()
                .filter(policy -> policy.executionCategory().readOnly())
                .map(policy -> policy.toolName())
                .collect(Collectors.toSet());
        for (String toolName : requested) {
            if (approved.contains(toolName)) {
                continue;
            }
            mcps.reviewTool(SYSTEM, component.id(), toolName, "READ_ONLY");
            log.info("AI platform bootstrap approved read-only tool: mcp={} tool={}", component.key(), toolName);
        }
    }

    private static void requireReportedTools(McpDefinition definition, Map<String, Object> report) {
        List<McpToolDescriptor> tools =
                JsonSupport.readList(JsonSupport.write(report.get("tools")), McpToolDescriptor.class);
        Set<String> names = tools.stream().map(McpToolDescriptor::name).collect(Collectors.toSet());
        if (!names.containsAll(definition.tools())) {
            throw new IllegalStateException("MCP " + definition.key() + " 缺少必需工具："
                    + definition.tools().stream()
                            .filter(tool -> !names.contains(tool))
                            .toList());
        }
    }

    private Credential ensureMcpCredential() {
        String secret = aiProperties.mcp() == null ? null : aiProperties.mcp().token();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("AI platform bootstrap: 未配置 IPE_AI_MCP_TOKEN");
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

    private static String nextPatchVersion(String current) {
        if (current == null || !current.matches("\\d+\\.\\d+\\.\\d+")) {
            return "1.0.0";
        }
        String[] parts = current.split("\\.");
        return parts[0] + "." + parts[1] + "." + (Integer.parseInt(parts[2]) + 1);
    }

    private static String nextUnusedPatchVersion(String current, Set<String> used) {
        String candidate = blank(current) ? "1.0.0" : current;
        Set<String> occupied = used == null ? Set.of() : new HashSet<>(used);
        while (occupied.contains(candidate)) {
            candidate = nextPatchVersion(candidate);
        }
        return candidate;
    }

    private List<SkillRelease> ensureSkills(PlatformProperties.Bootstrap config) {
        String version =
                blank(config.skillVersion()) ? "1.0.0" : config.skillVersion().strip();
        return skills.list().stream()
                .filter(item ->
                        item.key().equals("protocol-analysis") || item.key().equals("device-offline-check"))
                .map(component -> ensureSkill(component, version))
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
                .orElseThrow(
                        () -> new IllegalStateException("Skill " + component.key() + " 发布后未找到 ACTIVE 版本 " + version));
    }

    private void ensureAgent(
            ModelProfile model,
            McpRelease opsMcp,
            McpRelease knowledgeMcp,
            List<SkillRelease> skillReleases,
            PlatformProperties.Bootstrap config) {
        AgentComponent agent = agents.list().stream()
                .filter(item -> item.key().equals("protocol-expert"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少 Agent 草稿 protocol-expert"));
        AgentRelease active = agentReleases(agent.id()).stream()
                .filter(item -> "ACTIVE".equals(item.state()))
                .findFirst()
                .orElse(null);
        if (agentMatches(active, model, opsMcp, knowledgeMcp, skillReleases)) {
            log.info("AI platform bootstrap: 协议专家复用 ACTIVE 版本 {}", active.version());
            return;
        }
        List<McpBindingInput> mcpBindings = new ArrayList<>();
        int executionOrder = 0;
        for (String tool : OPS_TOOLS) {
            mcpBindings.add(new McpBindingInput(opsMcp.id(), tool, true, executionOrder++));
        }
        for (String tool : KNOWLEDGE_TOOLS) {
            mcpBindings.add(new McpBindingInput(knowledgeMcp.id(), tool, true, executionOrder++));
        }
        List<SkillBindingInput> skillBindings = new ArrayList<>();
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
                PROTOCOL_EXPERT_PROMPT.strip(),
                agent.modelOptions(),
                agent.budget(),
                agent.enabled(),
                mcpBindings,
                skillBindings);
        String version = nextUnusedPatchVersion(
                active == null
                        ? (blank(config.skillVersion())
                                ? "1.0.0"
                                : config.skillVersion().strip())
                        : active.version(),
                agentReleases(agent.id()).stream().map(AgentRelease::version).collect(Collectors.toSet()));
        AgentService.PublishResult published = agents.publish(
                SYSTEM, updated.id(), version, updated.revision(), "bootstrap-agent-protocol-expert-" + version);
        if (!awaitPublication(published.publicationId())) {
            throw new IllegalStateException("Agent protocol-expert 发布未在时限内完成");
        }
        AgentRelease publishedAgent = agentReleases(agent.id()).stream()
                .filter(item -> "ACTIVE".equals(item.state()))
                .findFirst()
                .orElse(null);
        if (!agentMatches(publishedAgent, model, opsMcp, knowledgeMcp, skillReleases)) {
            throw new IllegalStateException("Agent protocol-expert 发布后 ACTIVE 清单不符合预期");
        }
        log.info(
                "AI platform bootstrap published agent={} version={} tools={} skills={}",
                agent.key(),
                version,
                mcpBindings.size(),
                skillBindings.size());
    }

    private void ensureKnowledgeIndex() {
        KnowledgeService.IngestReport report = knowledge.ingestAll(SYSTEM, true);
        if (report == null) {
            throw new IllegalStateException("知识索引 bootstrap 未返回报告");
        }
        if (report.failed() > 0) {
            throw new IllegalStateException("知识索引 bootstrap 存在失败文档：failed=" + report.failed());
        }
        if (report.indexed() + report.skipped() <= 0) {
            throw new IllegalStateException("知识索引 bootstrap 未生成可用索引");
        }
        log.info(
                "AI platform bootstrap indexed knowledge: total={} indexed={} skipped={}",
                report.total(),
                report.indexed(),
                report.skipped());
    }

    private boolean agentMatches(
            AgentRelease active,
            ModelProfile model,
            McpRelease opsMcp,
            McpRelease knowledgeMcp,
            List<SkillRelease> skillReleases) {
        if (active == null) {
            return false;
        }
        AgentManifest manifest = active.manifest();
        if (!PROTOCOL_EXPERT_PROMPT.strip().equals(text(manifest.systemPrompt()))
                || !model.key().equals(text(manifest.model().profileKey()))
                || !model.baseUrl().equals(text(manifest.model().baseUrl()))) {
            return false;
        }
        Map<String, AgentManifest.McpBinding> actualMcps = manifest.mcp().stream()
                .collect(Collectors.toMap(AgentManifest.McpBinding::key, binding -> binding, (left, right) -> right));
        if (!actualMcps.keySet().equals(Set.of(OPS_MCP_KEY, KNOWLEDGE_MCP_KEY))) {
            return false;
        }
        if (!mcpBindingMatches(actualMcps.get(OPS_MCP_KEY), opsMcp, OPS_TOOLS)
                || !mcpBindingMatches(actualMcps.get(KNOWLEDGE_MCP_KEY), knowledgeMcp, KNOWLEDGE_TOOLS)) {
            return false;
        }
        Map<String, String> actualSkills = manifest.skills().stream()
                .collect(Collectors.toMap(
                        AgentManifest.SkillBinding::key, AgentManifest.SkillBinding::version, (left, right) -> right));
        Map<String, String> expectedSkills = skillReleases.stream()
                .collect(Collectors.toMap(SkillRelease::componentKey, SkillRelease::version, (left, right) -> right));
        return actualSkills.equals(expectedSkills);
    }

    private static boolean mcpBindingMatches(
            AgentManifest.McpBinding binding, McpRelease release, List<String> expectedTools) {
        if (binding == null
                || release == null
                || !binding.required()
                || !release.version().equals(binding.version())) {
            return false;
        }
        Set<String> names =
                binding.tools().stream().map(AgentManifest.McpToolBinding::name).collect(Collectors.toSet());
        return names.equals(new HashSet<>(expectedTools));
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

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static String firstNonBlank(String first, String second) {
        return blank(first) ? second : first;
    }

    void runOnce() {
        bootstrap();
    }

    record McpDefinition(String key, String displayName, String description, String endpoint, List<String> tools) {}
}
