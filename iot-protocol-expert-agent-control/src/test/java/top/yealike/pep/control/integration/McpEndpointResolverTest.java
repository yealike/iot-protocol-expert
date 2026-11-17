package top.yealike.pep.control.integration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import top.yealike.pep.control.config.AiProperties;

class McpEndpointResolverTest {
    @Test
    void explicitHigressEndpointWinsEvenWithLegacyRegistryFlagEnabled() {
        var resolver = new McpEndpointResolver(properties("https://gateway.example.com/mcp/ops/v1"));
        assertEquals(
                "https://gateway.example.com/mcp/ops/v1", resolver.endpoint().toString());
        assertEquals("higress", resolver.snapshot().source());
    }

    @Test
    void buildsConfiguredGatewayRoute() {
        assertEquals(
                "http://gateway:8088/ipe-mcp/ipe-ops-rest",
                new McpEndpointResolver(properties(null)).endpoint().toString());
    }

    @Test
    void rejectsNonHttpAndCredentialBearingEndpoints() {
        for (String value :
                new String[] {"file:/etc/passwd", "http://user:pw@host/mcp", "http://host/mcp#fragment", " "}) {
            assertThrows(IllegalArgumentException.class, () -> McpEndpointResolver.requireHttp(value));
        }
    }

    private static AiProperties properties(String fallbackUrl) {
        return new AiProperties(
                "local",
                new AiProperties.Mcp(
                        "token",
                        fallbackUrl,
                        "http://gateway:8088",
                        new AiProperties.Registry(true, "ipe-ops-rest", null, "public")),
                8000,
                null,
                null,
                null,
                null,
                null);
    }
}
