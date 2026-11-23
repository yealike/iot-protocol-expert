package top.yealike.pep.runtime.mcp;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import top.yealike.pep.runtime.contract.McpDescriptor;
import top.yealike.pep.runtime.contract.McpEndpointPolicy;

class McpEndpointResolverTest {
    @Test
    void preservesPublishedEndpointDespiteLegacyDiscoveryMetadata() {
        var descriptor = descriptor("https://gateway.example.com/mcp/ops/v1?tenant=1");
        assertThat(new McpEndpointResolver().resolve(descriptor)).isSameAs(descriptor);
        assertThat(descriptor.endpoint()).isEqualTo("https://gateway.example.com/mcp/ops/v1?tenant=1");
    }

    @Test
    void rejectsInvalidPublishedEndpoint() {
        assertThatThrownBy(() -> new McpEndpointResolver().resolve(descriptor("file:///tmp/mcp")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static McpDescriptor descriptor(String endpoint) {
        return new McpDescriptor(
                "ops",
                "1.0.0",
                "STREAMABLE_HTTP",
                endpoint,
                null,
                0,
                "catalog",
                List.of(),
                new McpEndpointPolicy(null, null, null, null, null, "legacy-nacos-entry", null, null));
    }
}
