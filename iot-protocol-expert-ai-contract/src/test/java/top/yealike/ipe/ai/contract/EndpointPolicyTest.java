package top.yealike.ipe.ai.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EndpointPolicyTest {

    @Test
    void keepsInternalAddressesAndNormalizesHttpEndpoints() {
        assertThat(EndpointPolicy.requireAllowed("HTTPS://10.0.0.8:8443/mcp?tenant=1"))
                .hasToString("https://10.0.0.8:8443/mcp?tenant=1");
    }

    @Test
    void rejectsLoopbackLinkLocalMulticastAndMetadataAddresses() {
        for (String endpoint : new String[] {
            "http://2852039166/",
            "http://[::ffff:169.254.169.254]/",
            "http://169.254.170.2/",
            "http://[fd00:ec2::254]/",
            "http://100.100.100.200/",
            "http://localhost/",
            "http://0.0.0.0/",
            "http://[::1]/",
            "http://224.0.0.1/"
        }) {
            assertThatThrownBy(() -> EndpointPolicy.requireAllowed(endpoint))
                    .as(endpoint)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsDnsNamesThatResolveToUnsafeAddresses() {
        assertThatThrownBy(() -> EndpointPolicy.requireAllowedAndResolvable("http://localhost:8080/mcp"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
