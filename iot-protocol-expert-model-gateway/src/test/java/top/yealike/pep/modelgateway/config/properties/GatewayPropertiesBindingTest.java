package top.yealike.pep.modelgateway.config.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class GatewayPropertiesBindingTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(BindingConfig.class);

    @Test
    void bindsKebabCaseEnvironmentIntoRecord() {
        runner.withPropertyValues(
                        "pep.gateway.service-token=service",
                        "pep.gateway.run-token-secret=secret",
                        "pep.gateway.credential-master-key=master",
                        "pep.gateway.public-base-url=http://gateway",
                        "pep.gateway.connect-timeout-ms=1234",
                        "pep.gateway.read-timeout-ms=5678",
                        "pep.gateway.dispatch-deadline-seconds=9",
                        "pep.gateway.reservation-ttl-seconds=60",
                        "pep.gateway.counting.chars-per-token=0.25",
                        "pep.gateway.counting.message-overhead=4",
                        "pep.gateway.counting.request-overhead=64",
                        "pep.gateway.counting.strict=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GatewayProperties properties = context.getBean(GatewayProperties.class);
                    assertThat(properties.connectTimeoutMs()).isEqualTo(1234);
                    assertThat(properties.counting().charsPerToken()).isEqualTo("0.25");
                    assertThat(properties.counting().strict()).isTrue();
                });
    }

    @Test
    void appliesRecordDefaultsWhenOptionalValuesAreMissing() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            GatewayProperties properties = context.getBean(GatewayProperties.class);
            assertThat(properties.connectTimeoutMs()).isEqualTo(10_000);
            assertThat(properties.counting().requestOverhead()).isEqualTo(256);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GatewayProperties.class)
    static class BindingConfig {}
}
