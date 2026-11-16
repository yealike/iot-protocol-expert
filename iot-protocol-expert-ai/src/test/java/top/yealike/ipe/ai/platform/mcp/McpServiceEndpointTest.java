package top.yealike.ipe.ai.platform.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.yealike.ipe.ai.contract.McpDescriptor;
import top.yealike.ipe.ai.platform.credential.CredentialService;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.McpRelease;
import top.yealike.ipe.ai.platform.registry.NacosRegistryService;
import top.yealike.ipe.ai.platform.store.CredentialStore;
import top.yealike.ipe.ai.platform.store.McpStore;
import top.yealike.ipe.ai.platform.store.PublicationStore;

/** Control 描述符必须保留发现实例的 HTTPS 与路径/查询契约，供 prepare 直接使用。 */
class McpServiceEndpointTest {

    @Test
    void keepsHttpsSchemeAndDiscoveredPathQuery() {
        UUID componentId = UUID.randomUUID();
        String serviceName = "ipe-mcp-ops-1-0-0";
        McpStore store = mock(McpStore.class);
        NacosRegistryService registry = mock(NacosRegistryService.class);
        when(store.findRelease("ops", "1.0.0")).thenReturn(Optional.of(release(componentId, serviceName)));
        when(store.toolCategories(componentId)).thenReturn(Map.of());
        when(registry.enabled()).thenReturn(true);
        when(registry.pickHealthy(serviceName))
                .thenReturn(new NacosRegistryService.InstanceView(
                        "10.0.0.2", 9443, Map.of("scheme", "https", "path", "/provider/mcp", "query", "tenant=1")));

        McpDescriptor descriptor = service(store, registry).descriptor("ops", "1.0.0");

        assertThat(descriptor.endpoint()).isEqualTo("https://10.0.0.2:9443/provider/mcp?tenant=1");
    }

    @Test
    void fallsBackToReleasePathAndQueryWhenMetadataOmitsThem() {
        UUID componentId = UUID.randomUUID();
        String serviceName = "ipe-mcp-ops-1-0-0";
        McpStore store = mock(McpStore.class);
        NacosRegistryService registry = mock(NacosRegistryService.class);
        when(store.findRelease("ops", "1.0.0")).thenReturn(Optional.of(release(componentId, serviceName)));
        when(store.toolCategories(componentId)).thenReturn(Map.of());
        when(registry.enabled()).thenReturn(true);
        when(registry.pickHealthy(serviceName))
                .thenReturn(new NacosRegistryService.InstanceView("10.0.0.2", 443, Map.of("scheme", "https")));

        assertThat(service(store, registry).descriptor("ops", "1.0.0").endpoint())
                .isEqualTo("https://10.0.0.2/mcp?tenant=1");
    }

    private static McpService service(McpStore store, NacosRegistryService registry) {
        return new McpService(
                store,
                mock(NacosMcpCatalogService.class),
                mock(McpProbe.class),
                mock(CredentialService.class),
                mock(CredentialStore.class),
                registry,
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
