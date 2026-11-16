package top.yealike.ipe.ai.observability;

import io.agentscope.core.tracing.TracerRegistry;
import io.agentscope.core.tracing.telemetry.TelemetryTracer;
import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import top.yealike.ipe.ai.config.AiProperties;

/**
 * 把 AgentScope 官方 TelemetryTracer 注册到应用现有的 OpenTelemetry SDK。
 *
 * <p>不另建 exporter，Span 继续走 Spring Boot 的 OTLP 管道，因此 trace_id 可以和 HTTP、JDBC、MCP
 * 以及异步 Run 队列的 Span 串在同一条链路上。
 *
 * <p>注意：AgentScope 2.0.4 中带内容采集的 {@code TelemetryTracer} 仍通过已标记待删除的
 * {@code TracerRegistry} 挂载；升级到 2.1+ 时需要重新确认官方 middleware 是否已支持内容属性。
 */
@Component
public class AgentScopeTelemetry implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeTelemetry.class);
    private static final String INSTRUMENTATION_NAME = "agentscope-java";

    private final OpenTelemetry openTelemetry;
    private final AiProperties properties;

    public AgentScopeTelemetry(OpenTelemetry openTelemetry, AiProperties properties) {
        this.openTelemetry = openTelemetry;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        AiProperties.Observability.Otel config = properties.observability() == null
                ? null
                : properties.observability().otel();
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
        if (config.contentEnabled()) {
            log.warn(
                    "AgentScope OTel tracing enabled with content capture; maxContentChars={}",
                    config.maxContentChars());
        } else {
            log.info("AgentScope OTel tracing enabled; prompt/response content is not exported");
        }
    }

    @Override
    public void destroy() {
        TracerRegistry.resetToNoop();
    }
}
