package top.yealike.pep.runtime.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.alibaba.nacos.api.naming.pojo.Instance;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.yealike.pep.runtime.TestFixtures;
import top.yealike.pep.runtime.contract.ComponentEndpoint;

class NacosRuntimeRegistryTest {

    @Test
    void staysReadyWithoutNacosWhenRegistrationIsDisabled() {
        NacosRuntimeRegistry registry = new NacosRuntimeRegistry(TestFixtures.properties());

        assertThat(registry.enabled()).isFalse();
        assertThat(registry.ready()).isTrue();

        assertThatCode(registry::onReady).doesNotThrowAnyException();
        assertThat(registry.ready()).isTrue();
        assertThatCode(() -> registry.registerAgent(UUID.randomUUID(), "protocol-expert", "1.0.0", "a".repeat(64)))
                .doesNotThrowAnyException();
        assertThatCode(() -> registry.deregisterAgent("protocol-expert", "1.0.0"))
                .doesNotThrowAnyException();
    }

    @Test
    void requiresInternalKeyEvenWhenRegistrationIsDisabled() {
        NacosRuntimeRegistry registry = new NacosRuntimeRegistry(TestFixtures.properties("http://127.0.0.1:19083", ""));

        assertThat(registry.enabled()).isFalse();
        assertThat(registry.ready()).isFalse();
    }

    @Test
    void buildsHttpsEndpointFromInstanceMetadata() {
        Instance instance =
                instance("10.0.0.2", 9443, Map.of("scheme", "HTTPS", "path", "/provider/mcp", "query", "tenant=1"));

        ComponentEndpoint endpoint = NacosRuntimeRegistry.endpointOf(instance);

        assertThat(endpoint.toUri()).isEqualTo("https://10.0.0.2:9443/provider/mcp?tenant=1");
    }

    @Test
    void omitsDefaultTlsPortFromInstanceMetadata() {
        Instance instance = instance("provider.example.com", 443, Map.of("scheme", "https", "path", "/mcp"));

        assertThat(NacosRuntimeRegistry.endpointOf(instance).toUri()).isEqualTo("https://provider.example.com/mcp");
    }

    @Test
    void defaultsToHttpWhenMetadataSchemeIsMissing() {
        Instance instance = instance("10.0.0.2", 8080, Map.of("path", "/mcp"));

        assertThat(NacosRuntimeRegistry.endpointOf(instance).toUri()).isEqualTo("http://10.0.0.2:8080/mcp");
    }

    private static Instance instance(String host, int port, Map<String, String> metadata) {
        Instance instance = new Instance();
        instance.setIp(host);
        instance.setPort(port);
        instance.setMetadata(metadata);
        return instance;
    }
}
