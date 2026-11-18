package top.yealike.pep.observability.protocol;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Installs the Logback appender declared by the shared logback-spring.xml.
 *
 * <p>The appender is initialized outside Spring, so every service that uses the shared logging
 * configuration must explicitly attach it to the application's OpenTelemetry SDK.
 */
@Configuration(proxyBeanMethods = false)
public class LogbackTelemetryConfiguration {

    @Bean
    InitializingBean openTelemetryLogbackInitializer(OpenTelemetry openTelemetry) {
        return () -> OpenTelemetryAppender.install(openTelemetry);
    }
}
