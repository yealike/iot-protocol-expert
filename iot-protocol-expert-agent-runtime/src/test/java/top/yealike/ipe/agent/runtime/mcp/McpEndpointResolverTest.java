package top.yealike.ipe.agent.runtime.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.yealike.ipe.agent.runtime.registry.NacosRuntimeRegistry;
import top.yealike.ipe.ai.contract.McpDescriptor;

class McpEndpointResolverTest {

    @Test
    void resolvesCurrentHealthyInstanceOnEveryCall() {
        NacosRuntimeRegistry registry = mock(NacosRuntimeRegistry.class);
        when(registry.enabled()).thenReturn(true);
        when(registry.discoverBaseUrl("ipe-mcp-ops-1-0-0")).thenReturn("https://10.0.0.2:9443");
        McpEndpointResolver resolver = new McpEndpointResolver(registry);
        McpDescriptor descriptor = new McpDescriptor(
                "ops",
                "1.0.0",
                "STREAMABLE_HTTP",
                "http://old-provider:8080/mcp",
                null,
                0L,
                "catalog",
                List.of(),
                Map.of("serviceName", "ipe-mcp-ops-1-0-0", "path", "/mcp"));

        McpDescriptor resolved = resolver.resolve(descriptor);

        assertThat(resolved.endpoint()).isEqualTo("https://10.0.0.2:9443/mcp");
        assertThat(resolved.catalogDigest()).isEqualTo("catalog");
    }

    @Test
    void failsInsteadOfFallingBackToStaticEndpointWhenRegistryIsEnabled() {
        NacosRuntimeRegistry registry = mock(NacosRuntimeRegistry.class);
        when(registry.enabled()).thenReturn(true);
        when(registry.discoverBaseUrl("ipe-mcp-ops-1-0-0")).thenReturn(null);
        McpEndpointResolver resolver = new McpEndpointResolver(registry);
        McpDescriptor descriptor = new McpDescriptor(
                "ops",
                "1.0.0",
                "STREAMABLE_HTTP",
                "http://old-provider:8080/mcp",
                null,
                0L,
                "catalog",
                List.of(),
                Map.of("serviceName", "ipe-mcp-ops-1-0-0", "path", "/mcp"));

        assertThatThrownBy(() -> resolver.resolve(descriptor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有健康实例");
    }
}
