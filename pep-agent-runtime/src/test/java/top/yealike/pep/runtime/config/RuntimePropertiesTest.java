package top.yealike.pep.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RuntimePropertiesTest {

    @Test
    void defaultsControlServiceNameToRegisteredControlService() {
        RuntimeProperties properties =
                new RuntimeProperties(19084, "", "runtime", "", "", "", "", "", null, null, null, null, null);

        assertThat(properties.controlServiceName()).isEqualTo("ipe-ai-control");
    }
}
