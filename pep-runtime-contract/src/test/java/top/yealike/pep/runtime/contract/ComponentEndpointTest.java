package top.yealike.pep.runtime.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ComponentEndpointTest {

    @Test
    void preservesHttpsSchemePathAndQuery() {
        ComponentEndpoint endpoint = ComponentEndpoint.parse("https://provider.example.com:9443/mcp?tenant=1");

        assertThat(endpoint.scheme()).isEqualTo("https");
        assertThat(endpoint.toUri()).isEqualTo("https://provider.example.com:9443/mcp?tenant=1");
    }

    @Test
    void omitsDefaultTlsPort() {
        ComponentEndpoint endpoint = ComponentEndpoint.of("HTTPS", "provider.example.com", 443, "mcp", "");

        assertThat(endpoint.toUri()).isEqualTo("https://provider.example.com/mcp");
    }

    @Test
    void rejectsUnsafeOrNonHttpEndpoints() {
        for (String value : new String[] {
            "file:/etc/passwd",
            "ftp://provider/mcp",
            "https://user:secret@provider/mcp",
            "https://provider/mcp#fragment",
            " "
        }) {
            assertThatThrownBy(() -> ComponentEndpoint.parse(value))
                    .as(value)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
