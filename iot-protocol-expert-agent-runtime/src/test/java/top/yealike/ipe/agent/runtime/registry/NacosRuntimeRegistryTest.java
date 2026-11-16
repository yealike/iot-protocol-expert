package top.yealike.ipe.agent.runtime.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.yealike.ipe.agent.runtime.TestFixtures;

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
}
