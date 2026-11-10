package top.yealike.ipe.protocol.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

@Configuration(proxyBeanMethods = false)
@Import({HttpTelemetryConfiguration.class, LocalTelemetryConfiguration.class})
public class ObservabilityConfiguration {
    @Bean
    ProtocolTelemetry protocolTelemetry(Tracer tracer, MeterRegistry meters) {
        return new ProtocolTelemetry(tracer, meters);
    }

    @Bean
    InitializingBean openTelemetryLogbackInitializer(OpenTelemetry openTelemetry) {
        return () -> OpenTelemetryAppender.install(openTelemetry);
    }

    /** Boot applies this to its auto-configured executor; custom executors must attach it too. */
    @Bean
    ContextPropagatingTaskDecorator contextPropagatingTaskDecorator() {
        return new ContextPropagatingTaskDecorator();
    }

    @Bean
    MeterFilter limitHttpRouteCardinality() {
        return MeterFilter.maximumAllowableTags("http.server.requests", "uri", 200, MeterFilter.deny());
    }

    @Bean
    ObservationFilter redactJdbcStatementAttributes() {
        return context -> {
            if (context.getName().startsWith("jdbc.")) {
                for (var value : context.getHighCardinalityKeyValues()) {
                    String key = value.getKey();
                    if (key.contains("query") || key.contains("param") || key.contains("statement")) {
                        context.removeHighCardinalityKeyValue(key);
                    }
                }
            }
            return context;
        };
    }
}
