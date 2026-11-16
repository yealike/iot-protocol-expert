package top.yealike.ipe.agent.runtime.prepare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.yealike.ipe.agent.runtime.TestFixtures;
import top.yealike.ipe.agent.runtime.config.RuntimeProperties;
import top.yealike.ipe.agent.runtime.control.ControlClient;
import top.yealike.ipe.agent.runtime.mcp.McpConnectionPool;
import top.yealike.ipe.agent.runtime.registry.NacosRuntimeRegistry;
import top.yealike.ipe.ai.contract.AgentManifest;
import top.yealike.ipe.ai.contract.ErrorCodes;
import top.yealike.ipe.ai.contract.ManifestDigests;
import top.yealike.ipe.ai.contract.McpDescriptor;
import top.yealike.ipe.ai.contract.ReleasePrepareRequest;
import top.yealike.ipe.ai.contract.ReleasePrepareResponse;
import top.yealike.ipe.ai.contract.SkillDescriptor;
import top.yealike.ipe.ai.contract.SkillFileInfo;

@ExtendWith(MockitoExtension.class)
class PrepareServiceTest {

    @Mock
    private ControlClient control;

    @Mock
    private McpConnectionPool mcpPool;

    @Mock
    private NacosRuntimeRegistry nacos;

    private PreparedReleaseRegistry releases;
    private PrepareService service;
    private RuntimeProperties properties;

    @BeforeEach
    void setUp() {
        properties = TestFixtures.properties();
        releases = new PreparedReleaseRegistry();
        service = new PrepareService(control, mcpPool, releases, nacos, properties);
        lenient().when(nacos.instanceId()).thenReturn("runtime-test");
        lenient().doNothing().when(nacos).registerAgent(any(), any(), any(), any());
    }

    @Test
    void preparesIdempotentlyAndStripsPrivateContextFromModelSchema() {
        UUID releaseId = UUID.randomUUID();
        AgentManifest manifest = TestFixtures.manifest(releaseId, true, List.of());
        String digest = ManifestDigests.digest(manifest);
        McpDescriptor descriptor = TestFixtures.mcpDescriptor();
        when(control.manifest(releaseId)).thenReturn(manifest);
        when(control.mcpDescriptor("ops", "1.0.0")).thenReturn(descriptor);
        doNothing().when(mcpPool).verify(eq(descriptor), isNull());

        ReleasePrepareRequest request = new ReleasePrepareRequest(releaseId, digest, 1L, UUID.randomUUID());
        ReleasePrepareResponse first = service.prepare(request);
        ReleasePrepareResponse second = service.prepare(request);

        assertThat(first.state()).isEqualTo("READY");
        assertThat(second.state()).isEqualTo("READY");
        assertThat(second.instanceId()).isEqualTo("runtime-test");
        assertThat(second.manifestDigest()).isEqualTo(digest);
        verify(control, times(1)).manifest(releaseId);
        verify(nacos, times(1)).registerAgent(eq(releaseId), eq("protocol-expert"), eq("1.0.0"), eq(digest));

        PreparedRelease release = releases.find(releaseId).orElseThrow();
        assertThat(release.tools()).hasSize(1);
        Map<String, Object> schema = release.tools().getFirst().parametersForModel();
        assertThat(schema.get("properties").toString()).doesNotContain("_ipe_context");
        assertThat(schema.get("required").toString()).doesNotContain("_ipe_context");
    }

    @Test
    void rejectsManifestDigestMismatch() {
        UUID releaseId = UUID.randomUUID();
        AgentManifest manifest = TestFixtures.manifest(releaseId, true, List.of());
        when(control.manifest(releaseId)).thenReturn(manifest);

        ReleasePrepareRequest request = new ReleasePrepareRequest(releaseId, "0".repeat(64), 1L, UUID.randomUUID());

        assertThatThrownBy(() -> service.prepare(request))
                .isInstanceOf(PrepareException.class)
                .satisfies(exception -> {
                    PrepareException prepareException = (PrepareException) exception;
                    assertThat(prepareException.status()).isEqualTo(422);
                    assertThat(prepareException.code()).isEqualTo("MANIFEST_DIGEST_MISMATCH");
                });
        verify(nacos, never()).registerAgent(any(), any(), any(), any());
    }

    @Test
    void rejectsRequiredMcpSchemaDrift() {
        UUID releaseId = UUID.randomUUID();
        AgentManifest manifest = TestFixtures.manifest(releaseId, true, List.of());
        String digest = ManifestDigests.digest(manifest);
        McpDescriptor descriptor = TestFixtures.mcpDescriptor();
        when(control.manifest(releaseId)).thenReturn(manifest);
        when(control.mcpDescriptor("ops", "1.0.0")).thenReturn(descriptor);
        doThrow(new PrepareException(422, ErrorCodes.SCHEMA_DRIFT, "schema drifted"))
                .when(mcpPool)
                .verify(eq(descriptor), isNull());

        ReleasePrepareRequest request = new ReleasePrepareRequest(releaseId, digest, 1L, UUID.randomUUID());

        assertThatThrownBy(() -> service.prepare(request))
                .isInstanceOf(PrepareException.class)
                .satisfies(exception ->
                        assertThat(((PrepareException) exception).code()).isEqualTo(ErrorCodes.SCHEMA_DRIFT));
    }

    @Test
    void degradesOptionalMcpIntoWarningAndRemovesTools() {
        UUID releaseId = UUID.randomUUID();
        AgentManifest manifest = TestFixtures.manifest(releaseId, false, List.of());
        String digest = ManifestDigests.digest(manifest);
        McpDescriptor descriptor = TestFixtures.mcpDescriptor();
        when(control.manifest(releaseId)).thenReturn(manifest);
        when(control.mcpDescriptor("ops", "1.0.0")).thenReturn(descriptor);
        doThrow(new PrepareException(503, ErrorCodes.MCP_INIT_FAILED, "offline"))
                .when(mcpPool)
                .verify(eq(descriptor), isNull());

        ReleasePrepareResponse response =
                service.prepare(new ReleasePrepareRequest(releaseId, digest, 1L, UUID.randomUUID()));

        assertThat(response.state()).isEqualTo("READY");
        assertThat(response.message()).contains("可选 MCP");
        assertThat(releases.find(releaseId).orElseThrow().tools()).isEmpty();
    }

    @Test
    void rejectsSkillPathTraversalBeforeCallingSkillProvider() {
        UUID releaseId = UUID.randomUUID();
        AgentManifest.SkillBinding binding =
                new AgentManifest.SkillBinding("protocol-analysis", "1.0.0", "a".repeat(64), "AUTO", 0);
        AgentManifest manifest = TestFixtures.manifestWithoutMcp(List.of(binding));
        when(control.manifest(releaseId)).thenReturn(manifest);
        when(control.skillDescriptor("protocol-analysis", "1.0.0"))
                .thenReturn(new SkillDescriptor(
                        "protocol-analysis",
                        "1.0.0",
                        "协议分析",
                        "desc",
                        "a".repeat(64),
                        List.of(new SkillFileInfo("../secret.md", "a".repeat(64), 10, false))));

        ReleasePrepareRequest request =
                new ReleasePrepareRequest(releaseId, ManifestDigests.digest(manifest), 1L, UUID.randomUUID());

        assertThatThrownBy(() -> service.prepare(request))
                .isInstanceOf(PrepareException.class)
                .satisfies(exception -> {
                    PrepareException prepareException = (PrepareException) exception;
                    assertThat(prepareException.code()).isEqualTo(ErrorCodes.SKILL_PATH_DENIED);
                });
        verify(control, never()).skillFile(any(), any(), any());
    }

    @Test
    void preparesLocallyWhenNacosRegistrationIsDisabled() {
        NacosRuntimeRegistry disabledRegistry = new NacosRuntimeRegistry(properties);
        PrepareService localService = new PrepareService(control, mcpPool, releases, disabledRegistry, properties);
        UUID releaseId = UUID.randomUUID();
        AgentManifest manifest = TestFixtures.manifestWithoutMcp(List.of());
        when(control.manifest(releaseId)).thenReturn(manifest);

        ReleasePrepareResponse response = localService.prepare(
                new ReleasePrepareRequest(releaseId, ManifestDigests.digest(manifest), 1L, UUID.randomUUID()));

        assertThat(response.state()).isEqualTo("READY");
        assertThat(response.instanceId()).startsWith("runtime-");
        assertThat(disabledRegistry.ready()).isTrue();
        assertThat(releases.find(releaseId)).isPresent();
    }
}
