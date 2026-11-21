package top.yealike.pep.control.agents.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import top.yealike.pep.control.agents.AgentService;
import top.yealike.pep.control.agents.config.PlatformProperties;
import top.yealike.pep.control.agents.credential.CredentialService;
import top.yealike.pep.control.agents.domain.PlatformRecords.AgentComponent;
import top.yealike.pep.control.agents.domain.PlatformRecords.AgentRelease;
import top.yealike.pep.control.agents.domain.PlatformRecords.Credential;
import top.yealike.pep.control.agents.domain.PlatformRecords.McpComponent;
import top.yealike.pep.control.agents.domain.PlatformRecords.McpRelease;
import top.yealike.pep.control.agents.domain.PlatformRecords.McpToolPolicy;
import top.yealike.pep.control.agents.domain.PlatformRecords.ModelProfile;
import top.yealike.pep.control.agents.domain.PlatformRecords.Publication;
import top.yealike.pep.control.agents.mcp.McpService;
import top.yealike.pep.control.agents.model.ModelProfileService;
import top.yealike.pep.control.agents.skill.SkillService;
import top.yealike.pep.control.agents.store.AgentStore.McpBindingInput;
import top.yealike.pep.control.agents.store.PublicationStore;
import top.yealike.pep.control.config.AiProperties;
import top.yealike.pep.control.config.ModelGatewayProperties;
import top.yealike.pep.control.knowledge.KnowledgeService;
import top.yealike.pep.runtime.contract.AgentManifest;
import top.yealike.pep.runtime.contract.McpToolDescriptor;
import top.yealike.pep.runtime.contract.McpToolExecutionCategory;

class PlatformBootstrapTest {

    private static final UUID OPS_COMPONENT_ID = UUID.randomUUID();
    private static final UUID KNOWLEDGE_COMPONENT_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID MODEL_ID = UUID.randomUUID();
    private static final UUID CREDENTIAL_ID = UUID.randomUUID();
    private static final String OPS_ENDPOINT = "https://192.0.2.10/mcp/ops/v2";
    private static final String KNOWLEDGE_ENDPOINT = "https://192.0.2.10/mcp/control/v1";

    @Test
    void createsKnowledgeMcpAndPublishesProtocolExpertWithFourTools() {
        Fixture fixture = new Fixture();
        fixture.addComponent(component(OPS_COMPONENT_ID, "ops"));
        fixture.stubPortfolio();

        fixture.bootstrap.runOnce();

        ArgumentCaptor<Map<String, Object>> draft = ArgumentCaptor.forClass(Map.class);
        verify(fixture.mcps)
                .create(
                        any(),
                        eq("control-knowledge"),
                        anyString(),
                        anyString(),
                        eq("REMOTE"),
                        anyMap(),
                        draft.capture());
        assertThat(draft.getValue()).containsEntry("endpoint", KNOWLEDGE_ENDPOINT);
        assertThat(draft.getValue()).containsEntry("contextMode", "OPS");
        assertThat(draft.getValue()).containsEntry("credentialId", CREDENTIAL_ID.toString());
        verify(fixture.mcps, org.mockito.Mockito.times(2))
                .publish(any(), any(), anyString(), any(), anyLong(), anyString());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<McpBindingInput>> mcpBindings = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(fixture.agents)
                .update(
                        eq(AGENT_ID),
                        eq(1L),
                        anyString(),
                        anyString(),
                        eq(MODEL_ID),
                        prompt.capture(),
                        anyMap(),
                        anyMap(),
                        eq(true),
                        mcpBindings.capture(),
                        any());
        assertThat(mcpBindings.getValue())
                .extracting(McpBindingInput::toolName)
                .containsExactly(
                        "resolve_device", "list_device_frames", "decode_device_frame", "search_protocol_knowledge");
        assertThat(prompt.getValue())
                .contains(
                        "resolve_device",
                        "list_device_frames",
                        "decode_device_frame",
                        "search_protocol_knowledge",
                        "protocol_document_id",
                        "frame_id",
                        "EVIDENCE_NOT_FOUND");
        verify(fixture.agents).publish(any(), eq(AGENT_ID), eq("1.0.0"), eq(2L), anyString());
        verify(fixture.knowledge).ingestAll(any(), eq(true));
    }

    @Test
    void reusesCompletePublishedMcpsAndAgentWithoutRepublishing() {
        Fixture fixture = new Fixture();
        fixture.addComponent(component(OPS_COMPONENT_ID, "ops"));
        fixture.addComponent(component(KNOWLEDGE_COMPONENT_ID, "control-knowledge"));
        fixture.addRelease(activeRelease(OPS_COMPONENT_ID, "ops", "1.0.0", opsTools()));
        fixture.addRelease(activeRelease(KNOWLEDGE_COMPONENT_ID, "control-knowledge", "1.0.0", knowledgeTools()));
        fixture.addAgentRelease(activeAgent(PlatformBootstrap.PROTOCOL_EXPERT_PROMPT, "1.0.0", "1.0.0"));
        fixture.stubPortfolio();

        fixture.bootstrap.runOnce();

        verify(fixture.mcps, never())
                .create(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), anyMap());
        verify(fixture.mcps, never()).test(any());
        verify(fixture.mcps, never()).publish(any(), any(), anyString(), any(), anyLong(), anyString());
        verify(fixture.agents, never())
                .update(
                        any(),
                        anyLong(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString(),
                        anyMap(),
                        anyMap(),
                        anyBoolean(),
                        any(),
                        any());
        verify(fixture.agents, never()).publish(any(), any(), anyString(), anyLong(), anyString());
    }

    @Test
    void republishesAgentWhenOnlyTheSystemPromptIsStale() {
        Fixture fixture = new Fixture();
        fixture.addComponent(component(OPS_COMPONENT_ID, "ops"));
        fixture.addComponent(component(KNOWLEDGE_COMPONENT_ID, "control-knowledge"));
        fixture.addRelease(activeRelease(OPS_COMPONENT_ID, "ops", "1.0.0", opsTools()));
        fixture.addRelease(activeRelease(KNOWLEDGE_COMPONENT_ID, "control-knowledge", "1.0.0", knowledgeTools()));
        fixture.addAgentRelease(activeAgent("旧提示词", "1.0.0", "1.0.0"));
        fixture.stubPortfolio();

        fixture.bootstrap.runOnce();

        verify(fixture.mcps, never()).test(any());
        verify(fixture.mcps, never()).publish(any(), any(), anyString(), any(), anyLong(), anyString());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(fixture.agents)
                .update(
                        eq(AGENT_ID),
                        eq(1L),
                        anyString(),
                        anyString(),
                        eq(MODEL_ID),
                        prompt.capture(),
                        anyMap(),
                        anyMap(),
                        eq(true),
                        any(),
                        any());
        assertThat(prompt.getValue()).isEqualTo(PlatformBootstrap.PROTOCOL_EXPERT_PROMPT.strip());
        verify(fixture.agents).publish(any(), eq(AGENT_ID), eq("1.0.1"), eq(2L), anyString());
    }

    @Test
    void failsSynchronouslyWhenOpsMcpEndpointIsMissing() {
        Fixture fixture = new Fixture(true, "", KNOWLEDGE_ENDPOINT);
        fixture.stubPortfolio();

        assertThatThrownBy(() -> fixture.bootstrap.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ops")
                .hasMessageContaining("endpoint");

        verify(fixture.agents, never()).publish(any(), any(), anyString(), anyLong(), anyString());
        verify(fixture.knowledge, never()).ingestAll(any(), anyBoolean());
    }

    @Test
    void failsSynchronouslyWhenKnowledgeMcpEndpointIsMissing() {
        Fixture fixture = new Fixture(true, OPS_ENDPOINT, "");
        fixture.stubPortfolio();

        assertThatThrownBy(() -> fixture.bootstrap.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("control-knowledge")
                .hasMessageContaining("endpoint");

        verify(fixture.agents, never()).publish(any(), any(), anyString(), anyLong(), anyString());
        verify(fixture.knowledge, never()).ingestAll(any(), anyBoolean());
    }

    @Test
    void leavesBootstrapDisabledAsNoOp() {
        Fixture fixture = new Fixture(false, "", "");

        assertThatCode(() -> fixture.bootstrap.run(mock(ApplicationArguments.class)))
                .doesNotThrowAnyException();

        verifyNoInteractions(fixture.credentials, fixture.mcps, fixture.agents, fixture.knowledge);
    }

    @Test
    void failsWhenManagedMcpCreationFails() {
        Fixture fixture = new Fixture();
        fixture.stubPortfolio();
        when(fixture.mcps.create(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), anyMap()))
                .thenThrow(new IllegalStateException("MCP create failed"));

        assertThatThrownBy(fixture.bootstrap::runOnce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP create failed");

        verify(fixture.agents, never()).publish(any(), any(), anyString(), anyLong(), anyString());
        verify(fixture.knowledge, never()).ingestAll(any(), anyBoolean());
    }

    @Test
    void failsWhenMcpProbeDoesNotReportRequiredTools() {
        Fixture fixture = new Fixture();
        fixture.addComponent(component(OPS_COMPONENT_ID, "ops"));
        fixture.addRelease(activeRelease(OPS_COMPONENT_ID, "ops", "1.0.0", opsTools()));
        fixture.addComponent(component(KNOWLEDGE_COMPONENT_ID, "control-knowledge"));
        fixture.stubPortfolio();
        when(fixture.mcps.test(KNOWLEDGE_COMPONENT_ID))
                .thenReturn(Map.of("reportId", UUID.randomUUID(), "tools", List.of()));

        assertThatThrownBy(fixture.bootstrap::runOnce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("control-knowledge")
                .hasMessageContaining("缺少必需工具");

        verify(fixture.mcps, never()).publish(any(), any(), anyString(), any(), anyLong(), anyString());
        verify(fixture.agents, never()).publish(any(), any(), anyString(), anyLong(), anyString());
    }

    @Test
    void failsWhenPublishedAgentDoesNotMatchExpectedActiveManifest() {
        Fixture fixture = new Fixture();
        fixture.addComponent(component(OPS_COMPONENT_ID, "ops"));
        fixture.addComponent(component(KNOWLEDGE_COMPONENT_ID, "control-knowledge"));
        fixture.addRelease(activeRelease(OPS_COMPONENT_ID, "ops", "1.0.0", opsTools()));
        fixture.addRelease(activeRelease(KNOWLEDGE_COMPONENT_ID, "control-knowledge", "1.0.0", knowledgeTools()));
        fixture.stubPortfolio();
        when(fixture.agents.publish(any(), any(), anyString(), anyLong(), anyString()))
                .thenAnswer(invocation -> new AgentService.PublishResult(
                        UUID.randomUUID(), UUID.randomUUID(), invocation.getArgument(2), "manifest"));

        assertThatThrownBy(fixture.bootstrap::runOnce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACTIVE")
                .hasMessageContaining("不符合预期");

        verify(fixture.knowledge, never()).ingestAll(any(), anyBoolean());
    }

    @Test
    void failsWhenKnowledgeIngestionHasFailedDocuments() {
        Fixture fixture = completePlatformFixture();
        when(fixture.knowledge.ingestAll(any(), eq(true)))
                .thenReturn(new KnowledgeService.IngestReport(2, 1, 0, 1, List.of()));

        assertThatThrownBy(fixture.bootstrap::runOnce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed=1");
    }

    @Test
    void failsWhenNoKnowledgeDocumentIsAvailable() {
        Fixture fixture = completePlatformFixture();
        when(fixture.knowledge.ingestAll(any(), eq(true)))
                .thenReturn(new KnowledgeService.IngestReport(0, 0, 0, 0, List.of()));

        assertThatThrownBy(fixture.bootstrap::runOnce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未生成可用索引");
    }

    private static Fixture completePlatformFixture() {
        Fixture fixture = new Fixture();
        fixture.addComponent(component(OPS_COMPONENT_ID, "ops"));
        fixture.addComponent(component(KNOWLEDGE_COMPONENT_ID, "control-knowledge"));
        fixture.addRelease(activeRelease(OPS_COMPONENT_ID, "ops", "1.0.0", opsTools()));
        fixture.addRelease(activeRelease(KNOWLEDGE_COMPONENT_ID, "control-knowledge", "1.0.0", knowledgeTools()));
        fixture.addAgentRelease(activeAgent(PlatformBootstrap.PROTOCOL_EXPERT_PROMPT, "1.0.0", "1.0.0"));
        fixture.stubPortfolio();
        return fixture;
    }

    private static KnowledgeService.IngestReport successfulIngestReport() {
        return new KnowledgeService.IngestReport(
                1, 1, 0, 0, List.of(new KnowledgeService.IngestResult("1", "GB/T 27930", "PUBLISHED", 3, null, false)));
    }

    private static List<String> opsTools() {
        return List.of("resolve_device", "list_device_frames", "decode_device_frame");
    }

    private static List<String> knowledgeTools() {
        return List.of("search_protocol_knowledge");
    }

    private static McpComponent component(UUID id, String key) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("endpoint", "ops".equals(key) ? OPS_ENDPOINT : KNOWLEDGE_ENDPOINT);
        draft.put("contextMode", "OPS");
        draft.put("credentialId", CREDENTIAL_ID.toString());
        draft.put("timeoutMs", 20000);
        return new McpComponent(
                id,
                key,
                key,
                key,
                "REMOTE",
                Map.of(),
                draft,
                "1.0.0",
                1,
                true,
                1,
                "system",
                Instant.now(),
                Instant.now());
    }

    private static McpRelease activeRelease(UUID componentId, String key, String version, List<String> toolNames) {
        Map<String, Object> endpointPolicy = new LinkedHashMap<>();
        endpointPolicy.put("endpoint", "ops".equals(key) ? OPS_ENDPOINT : KNOWLEDGE_ENDPOINT);
        endpointPolicy.put("contextMode", "OPS");
        endpointPolicy.put("timeoutMs", 20000);
        return new McpRelease(
                UUID.randomUUID(),
                componentId,
                key,
                version,
                "STREAMABLE_HTTP",
                endpointPolicy,
                CREDENTIAL_ID,
                toolNames.stream().map(PlatformBootstrapTest::tool).toList(),
                "catalog-" + version,
                "content-" + version,
                "ACTIVE",
                1,
                Instant.now());
    }

    private static McpToolDescriptor tool(String name) {
        return new McpToolDescriptor(
                name, name, Map.of("type", "object"), Map.of(), Map.of("readOnlyHint", true), "schema-" + name, true);
    }

    private static AgentRelease activeAgent(String prompt, String mcpVersion, String agentVersion) {
        List<AgentManifest.McpBinding> mcps = List.of(
                mcpBinding("ops", mcpVersion, opsTools()),
                mcpBinding("control-knowledge", mcpVersion, knowledgeTools()));
        AgentManifest manifest = new AgentManifest(
                "ipe.agent/v1",
                "protocol-expert",
                agentVersion,
                "协议排障专家",
                prompt,
                new AgentManifest.ModelSpec(
                        "deepseek-default",
                        "OPENAI_COMPAT",
                        "http://127.0.0.1:19085/v1",
                        "deepseek-default",
                        "model-default",
                        1,
                        Map.of()),
                List.of(),
                mcps,
                new AgentManifest.Budget(6, 12, 120_000),
                new AgentManifest.ExecutionPolicy(false, "DENY"));
        return new AgentRelease(
                UUID.randomUUID(),
                AGENT_ID,
                "protocol-expert",
                agentVersion,
                manifest,
                "manifest-" + agentVersion,
                "ACTIVE",
                1,
                1,
                Instant.now());
    }

    private static AgentManifest.McpBinding mcpBinding(String key, String version, List<String> toolNames) {
        return new AgentManifest.McpBinding(
                key,
                version,
                true,
                "catalog-" + version,
                toolNames.stream()
                        .map(name -> new AgentManifest.McpToolBinding(name, name, "schema-" + name))
                        .toList());
    }

    private static ModelProfile model() {
        return new ModelProfile(
                MODEL_ID,
                "deepseek-default",
                "DeepSeek",
                "",
                "OPENAI_COMPAT",
                "http://127.0.0.1:19085/v1",
                "deepseek-default",
                null,
                null,
                Map.of(),
                true,
                1,
                "system",
                Instant.now(),
                Instant.now());
    }

    private static Credential credential() {
        return new Credential(CREDENTIAL_ID, "mcp-ops", "OPS MCP", 1, true, 1, "system", Instant.now(), Instant.now());
    }

    private static AgentComponent agent() {
        return new AgentComponent(
                AGENT_ID,
                "protocol-expert",
                "协议排障专家",
                "",
                MODEL_ID,
                "旧提示词",
                Map.of(),
                Map.of("maxIterations", 6, "maxToolCalls", 12, "runTimeoutMs", 120000),
                "1.0.0",
                1,
                true,
                1,
                "system",
                Instant.now(),
                Instant.now());
    }

    private static Publication published() {
        Instant now = Instant.now();
        return new Publication(
                UUID.randomUUID(),
                "AGENT",
                AGENT_ID,
                "1.0.0",
                1,
                "PUBLISHED",
                "READY",
                1,
                "idempotency",
                null,
                null,
                1,
                now,
                now,
                now,
                now);
    }

    private static final class Fixture {

        final CredentialService credentials = mock(CredentialService.class);
        final ModelProfileService models = mock(ModelProfileService.class);
        final McpService mcps = mock(McpService.class);
        final SkillService skills = mock(SkillService.class);
        final AgentService agents = mock(AgentService.class);
        final PublicationStore publications = mock(PublicationStore.class);
        final KnowledgeService knowledge = mock(KnowledgeService.class);
        final Map<UUID, McpComponent> components = new ConcurrentHashMap<>();
        final Map<UUID, McpRelease> activeMcps = new ConcurrentHashMap<>();
        final Map<UUID, List<AgentRelease>> agentReleases = new ConcurrentHashMap<>();
        final PlatformBootstrap bootstrap;
        private AgentComponent agent = agent();

        Fixture() {
            this(true, OPS_ENDPOINT, KNOWLEDGE_ENDPOINT);
        }

        Fixture(boolean enabled) {
            this(enabled, OPS_ENDPOINT, KNOWLEDGE_ENDPOINT);
        }

        Fixture(boolean enabled, String opsEndpoint, String knowledgeEndpoint) {
            PlatformProperties.Bootstrap config = new PlatformProperties.Bootstrap(
                    enabled,
                    "model-default",
                    "",
                    "",
                    "deepseek-default",
                    "1.0.0",
                    List.of(),
                    opsEndpoint,
                    knowledgeEndpoint,
                    List.of());
            PlatformProperties properties = new PlatformProperties(null, null, null, null, config);
            AiProperties aiProperties = new AiProperties(
                    "test", new AiProperties.Mcp("test-token", opsEndpoint, "", null), 1000, null, null, null, null);
            bootstrap = new PlatformBootstrap(
                    properties,
                    aiProperties,
                    credentials,
                    models,
                    mcps,
                    skills,
                    agents,
                    publications,
                    new ModelGatewayProperties(),
                    knowledge);
        }

        void addComponent(McpComponent component) {
            components.put(component.id(), component);
        }

        void addRelease(McpRelease release) {
            activeMcps.put(release.componentId(), release);
        }

        void addAgentRelease(AgentRelease release) {
            agentReleases.put(AGENT_ID, List.of(release));
            agent = new AgentComponent(
                    agent.id(),
                    agent.key(),
                    agent.displayName(),
                    agent.description(),
                    agent.modelProfileId(),
                    release.manifest().systemPrompt(),
                    agent.modelOptions(),
                    agent.budget(),
                    release.version(),
                    1,
                    true,
                    agent.revision(),
                    agent.createdBy(),
                    agent.createdAt(),
                    agent.updatedAt());
        }

        void stubPortfolio() {
            when(credentials.list()).thenReturn(List.of(credential()));
            when(models.list()).thenReturn(List.of(model()));
            when(mcps.list()).thenAnswer(ignored -> new ArrayList<>(components.values()));
            when(mcps.require(any()))
                    .thenAnswer(invocation -> Optional.ofNullable(components.get(invocation.getArgument(0)))
                            .orElseThrow());
            when(mcps.releases(any()))
                    .thenAnswer(invocation -> Optional.ofNullable(activeMcps.get(invocation.getArgument(0)))
                            .map(List::of)
                            .orElseGet(List::of));
            when(mcps.toolPolicies(any())).thenAnswer(invocation -> {
                String key = components.get(invocation.getArgument(0)).key();
                List<String> names = "ops".equals(key) ? opsTools() : knowledgeTools();
                return names.stream()
                        .map(name -> new McpToolPolicy(
                                invocation.getArgument(0),
                                name,
                                McpToolExecutionCategory.READ_ONLY,
                                "system",
                                Instant.now()))
                        .toList();
            });
            when(mcps.reviewTool(any(), any(), anyString(), anyString()))
                    .thenAnswer(invocation -> new McpToolPolicy(
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            McpToolExecutionCategory.READ_ONLY,
                            "system",
                            Instant.now()));
            when(mcps.create(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), anyMap()))
                    .thenAnswer(invocation -> {
                        UUID id = "control-knowledge".equals(invocation.getArgument(1))
                                ? KNOWLEDGE_COMPONENT_ID
                                : UUID.randomUUID();
                        addComponent(new McpComponent(
                                id,
                                invocation.getArgument(1),
                                invocation.getArgument(2),
                                invocation.getArgument(3),
                                invocation.getArgument(4),
                                Map.of(),
                                invocation.getArgument(6),
                                null,
                                0,
                                true,
                                1,
                                "system",
                                Instant.now(),
                                Instant.now()));
                        return id;
                    });
            when(mcps.test(any())).thenAnswer(invocation -> {
                UUID componentId = invocation.getArgument(0);
                String key = components.get(componentId).key();
                List<McpToolDescriptor> tools = ("ops".equals(key) ? opsTools() : knowledgeTools())
                        .stream().map(PlatformBootstrapTest::tool).toList();
                Map<String, Object> report = new HashMap<>();
                report.put("reportId", UUID.randomUUID());
                report.put("tools", tools);
                return report;
            });
            when(mcps.publish(any(), any(), anyString(), any(), anyLong(), anyString()))
                    .thenAnswer(invocation -> {
                        UUID componentId = invocation.getArgument(1);
                        String version = invocation.getArgument(2);
                        McpComponent component = components.get(componentId);
                        List<String> toolNames = "ops".equals(component.key()) ? opsTools() : knowledgeTools();
                        McpRelease release = activeRelease(componentId, component.key(), version, toolNames);
                        activeMcps.put(componentId, release);
                        return new McpService.PublishResult(
                                release.id(), UUID.randomUUID(), version, release.catalogDigest());
                    });
            when(skills.list()).thenReturn(List.of());
            when(knowledge.ingestAll(any(), eq(true))).thenReturn(successfulIngestReport());
            when(agents.list()).thenReturn(List.of(agent));
            when(agents.require(AGENT_ID)).thenAnswer(ignored -> agent);
            when(agents.releases(AGENT_ID)).thenAnswer(ignored -> agentReleases.getOrDefault(AGENT_ID, List.of()));
            when(agents.update(
                            any(),
                            anyLong(),
                            anyString(),
                            anyString(),
                            any(),
                            anyString(),
                            anyMap(),
                            anyMap(),
                            anyBoolean(),
                            any(),
                            any()))
                    .thenAnswer(invocation -> {
                        List<McpBindingInput> bindings = invocation.getArgument(9);
                        agent = new AgentComponent(
                                agent.id(),
                                agent.key(),
                                agent.displayName(),
                                agent.description(),
                                agent.modelProfileId(),
                                invocation.getArgument(5),
                                invocation.getArgument(6),
                                invocation.getArgument(7),
                                agent.publishedVersion(),
                                agent.publicationGeneration(),
                                agent.enabled(),
                                agent.revision() + 1,
                                agent.createdBy(),
                                agent.createdAt(),
                                Instant.now());
                        assertThat(bindings).hasSize(4);
                        return agent;
                    });
            when(agents.publish(any(), any(), anyString(), anyLong(), anyString()))
                    .thenAnswer(invocation -> {
                        String version = invocation.getArgument(2);
                        AgentRelease release = activeAgent(
                                agent.systemPrompt(),
                                activeMcps.get(OPS_COMPONENT_ID).version(),
                                version);
                        agentReleases.put(AGENT_ID, List.of(release));
                        return new AgentService.PublishResult(
                                UUID.randomUUID(), UUID.randomUUID(), version, "manifest");
                    });
            when(publications.find(any())).thenReturn(Optional.of(published()));
        }
    }
}
