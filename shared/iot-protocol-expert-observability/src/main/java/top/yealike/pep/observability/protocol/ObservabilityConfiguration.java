package top.yealike.pep.observability.protocol;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.contextpropagation.ObservationAwareSpanThreadLocalAccessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

@Configuration(proxyBeanMethods = false)
@Import({HttpTelemetryConfiguration.class, LocalTelemetryConfiguration.class, LogbackTelemetryConfiguration.class})
public class ObservabilityConfiguration {
    @Bean
    ProtocolTelemetry protocolTelemetry(Tracer tracer, MeterRegistry meters) {
        return new ProtocolTelemetry(tracer, meters);
    }

    /** Boot applies this to its auto-configured executor; custom executors must attach it too. */
    @Bean
    TaskDecorator contextPropagatingTaskDecorator(ObservationRegistry observationRegistry, Tracer tracer) {
        ContextRegistry registry = new ContextRegistry()
                .registerThreadLocalAccessor(new ObservationThreadLocalAccessor(observationRegistry))
                .registerThreadLocalAccessor(new ObservationAwareSpanThreadLocalAccessor(observationRegistry, tracer))
                .registerThreadLocalAccessor(new Slf4jThreadLocalAccessor("traceId", "spanId"));
        return new ContextPropagatingTaskDecorator(
                ContextSnapshotFactory.builder().contextRegistry(registry).build());
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
