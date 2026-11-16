package top.yealike.ipe.ops.telemetry;

import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.ObservationFilter;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

/** 与协议通道、模拟器一致的 OTel 接入方式：Boot 自动装配 + OTLP HTTP + 结构化日志。 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfiguration {

    @Bean
    InitializingBean openTelemetryLogbackInitializer(OpenTelemetry openTelemetry) {
        return () -> OpenTelemetryAppender.install(openTelemetry);
    }

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
