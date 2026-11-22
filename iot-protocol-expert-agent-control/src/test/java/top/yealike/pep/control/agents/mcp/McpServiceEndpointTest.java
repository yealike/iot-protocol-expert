package top.yealike.pep.control.agents.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.yealike.pep.control.agents.credential.CredentialService;
import top.yealike.pep.control.agents.domain.McpRelease;
import top.yealike.pep.control.agents.store.CredentialStore;
import top.yealike.pep.control.agents.store.McpStore;
import top.yealike.pep.control.agents.store.PublicationStore;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.runtime.contract.McpDescriptor;

/** Control 描述符使用不可变发布入口；发现服务不能替换已审核的 MCP 入口。 */
class McpServiceEndpointTest {

    @Test
    void keepsPublishedEndpointDespiteDiscoveredReplacement() {
        UUID componentId = UUID.randomUUID();
        String serviceName = "ipe-mcp-ops-1-0-0";
        McpStore store = mock(McpStore.class);
        when(store.findRelease("ops", "1.0.0")).thenReturn(Optional.of(release(componentId, serviceName)));
        when(store.toolCategories(componentId)).thenReturn(Map.of());

        McpDescriptor descriptor = service(store).descriptor("ops", "1.0.0");

        assertThat(descriptor.endpoint()).isEqualTo("http://old-provider:8080/mcp");
    }

    @Test
    void keepsPublishedEndpointWhenRegistryMetadataIsIncomplete() {
        UUID componentId = UUID.randomUUID();
        String serviceName = "ipe-mcp-ops-1-0-0";
        McpStore store = mock(McpStore.class);
        when(store.findRelease("ops", "1.0.0")).thenReturn(Optional.of(release(componentId, serviceName)));
        when(store.toolCategories(componentId)).thenReturn(Map.of());

        assertThat(service(store).descriptor("ops", "1.0.0").endpoint()).isEqualTo("http://old-provider:8080/mcp");
    }

    @Test
    void keepsValidatedEndpointPathAndQuery() {
        assertThat(McpService.validatedEndpoint("https://example.com:8443/mcp?tenant=1"))
                .isEqualTo("https://example.com:8443/mcp?tenant=1");
    }

    @Test
    void rejectsNonHttpAndCredentialBearingEndpoints() {
        assertThatThrownBy(() -> McpService.validatedEndpoint("file:///tmp/mcp"))
                .isInstanceOf(AiExceptions.AiException.class)
                .hasMessageContaining("http(s)");
        assertThatThrownBy(() -> McpService.validatedEndpoint("http://user:secret@example.com/mcp"))
                .isInstanceOf(AiExceptions.AiException.class)
                .hasMessageContaining("http(s)");
    }

    @Test
    void rejectsCloudMetadataAddresses() {
        for (String endpoint : List.of(
                "http://2852039166/latest/meta-data",
                "http://169.254.169.254/latest/meta-data",
                "http://[::ffff:169.254.169.254]/latest/meta-data",
                "http://metadata.google.internal./computeMetadata",
                "http://100.100.100.200/latest/meta-data",
                "http://[fd00:ec2::254]/latest/meta-data",
                "http://[fe80::1]/mcp")) {
            assertThatThrownBy(() -> McpService.validatedEndpoint(endpoint))
                    .isInstanceOf(AiExceptions.AiException.class)
                    .hasMessageContaining("云元数据");
        }
    }

    private static McpService service(McpStore store) {
        return new McpService(
                store,
                mock(NacosMcpCatalogService.class),
                mock(McpProbe.class),
                mock(CredentialService.class),
                mock(CredentialStore.class),
                mock(PublicationStore.class));
    }

    private static McpRelease release(UUID componentId, String serviceName) {
        return new McpRelease(
                UUID.randomUUID(),
                componentId,
                "ops",
                "1.0.0",
                "STREAMABLE_HTTP",
                Map.of(
                        "endpoint",
                        "http://old-provider:8080/mcp",
                        "path",
                        "/mcp",
                        "query",
                        "tenant=1",
                        "serviceName",
                        serviceName),
                null,
                List.of(),
                "catalog",
                "content",
                "ACTIVE",
                7L,
                Instant.now());
    }
}
