package top.yealike.ipe.ai.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.alibaba.nacos.api.ai.model.mcp.McpEndpointInfo;
import com.alibaba.nacos.api.ai.model.mcp.McpServerDetailInfo;
import java.util.List;
import org.junit.jupiter.api.Test;
import top.yealike.ipe.ai.config.AiProperties;

class McpEndpointResolverTest {
    @Test
    void prefersRegistryFrontendOverConfiguredGatewayRoute() {
        var endpoint = new McpEndpointInfo();
        endpoint.setAddress("10.0.0.7");
        endpoint.setPort(8088);
        endpoint.setProtocol("http");
        endpoint.setPath("/registry/mcp");
        var detail = new McpServerDetailInfo();
        detail.setFrontendEndpoints(List.of(endpoint));

        assertEquals(
                "http://10.0.0.7:8088/registry/mcp",
                McpEndpointResolver.resolveEndpoint(detail, properties("http://fallback:8088/mcp"))
                        .toString());
    }

    @Test
    void buildsGatewayRouteWhenRegistryHasNoFrontend() {
        var properties = properties(null);

        assertEquals(
                "http://gateway:8088/ipe-mcp/ipe-ops-rest",
                McpEndpointResolver.resolveEndpoint(new McpServerDetailInfo(), properties)
                        .toString());
    }

    @Test
    void buildsGatewayRouteFromBaseAndServerName() {
        assertEquals(
                "http://gateway:8088/ipe-mcp/ipe-ops-rest",
                McpEndpointResolver.gatewayEndpoint("http://gateway:8088/", "ipe-ops-rest")
                        .toString());
    }

    @Test
    void usesRegistryFrontendEndpointWhenPresent() {
        var endpoint = new McpEndpointInfo();
        endpoint.setAddress("10.0.0.7");
        endpoint.setPort(8443);
        endpoint.setProtocol("https");
        endpoint.setPath("/ipe-mcp/ipe-ops-rest");
        assertEquals(
                "https://10.0.0.7:8443/ipe-mcp/ipe-ops-rest",
                McpEndpointResolver.frontendEndpoint(endpoint).toString());
    }

    @Test
    void rejectsEndpointsThatAreNotPlainHttp() {
        for (String value : new String[] {
            "file:/etc/passwd", "http://user:pw@host/mcp", "http://host/mcp#fragment", "mcp-streamable://host", " "
        }) {
            assertThrows(IllegalArgumentException.class, () -> McpEndpointResolver.requireHttp(value), value);
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
                null);
    }
}
