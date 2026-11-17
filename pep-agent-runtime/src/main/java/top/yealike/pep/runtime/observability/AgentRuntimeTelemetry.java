package top.yealike.pep.runtime.observability;

import io.agentscope.core.tracing.TracerRegistry;
import io.agentscope.core.tracing.telemetry.TelemetryTracer;
import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import top.yealike.pep.runtime.config.RuntimeProperties;
import top.yealike.pep.observability.ContentFilteringTracer;

/** 在真正执行 Agent/模型的 Runtime 进程注册 AgentScope 官方 GenAI 埋点。 */
@Component
public class AgentRuntimeTelemetry implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeTelemetry.class);
    private static final String INSTRUMENTATION_NAME = "agentscope-java";

    private final OpenTelemetry openTelemetry;
    private final RuntimeProperties.Otel config;

    public AgentRuntimeTelemetry(OpenTelemetry openTelemetry, RuntimeProperties properties) {
        this.openTelemetry = openTelemetry;
        this.config = properties.observability() == null
                ? null
                : properties.observability().otel();
    }

    @Override
    public void afterPropertiesSet() {
        if (config == null || !config.enabled()) {
            TracerRegistry.resetToNoop();
            log.info("AgentScope OTel tracing disabled");
            return;
        }
        var tracer = TelemetryTracer.builder()
                .tracer(new ContentFilteringTracer(
                        openTelemetry.getTracer(INSTRUMENTATION_NAME),
                        config.contentEnabled(),
                        config.maxContentChars()))
                .build();
        TracerRegistry.register(tracer);
        log.info(
                "AgentScope OTel tracing enabled: contentEnabled={} maxContentChars={}",
                config.contentEnabled(),
                config.maxContentChars());
    }

    @Override
    public void destroy() {
        TracerRegistry.resetToNoop();
    }
}
