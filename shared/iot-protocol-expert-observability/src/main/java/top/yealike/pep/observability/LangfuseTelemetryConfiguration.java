package top.yealike.pep.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SdkTracerProviderBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 Agent/GenAI span 额外导出到 Langfuse 的 OTLP 入口。
 *
 * <p>与 Tempo 链路并存：只转发 AgentScope 埋点或带 gen_ai.* 语义属性的 span，
 * 避免把普通 HTTP/DB span 灌进 Langfuse 影响评测视图。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ipe.langfuse", name = "enabled", havingValue = "true")
public class LangfuseTelemetryConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LangfuseTelemetryConfiguration.class);

    @Bean
    SdkTracerProviderBuilderCustomizer langfuseSpanExporter(
            @Value("${ipe.langfuse.server-url:}") String serverUrl,
            @Value("${ipe.langfuse.public-key:}") String publicKey,
            @Value("${ipe.langfuse.secret-key:}") String secretKey) {
        if (serverUrl.isBlank() || publicKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("ipe.langfuse.enabled=true 时必须配置 server-url / public-key / secret-key");
        }
        String endpoint = serverUrl.replaceAll("/+$", "") + "/api/public/otel/v1/traces";
        String authorization = "Basic "
                + Base64.getEncoder().encodeToString((publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
        SpanExporter otlp = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .addHeader("Authorization", authorization)
                .setTimeout(15, TimeUnit.SECONDS)
                .build();
        SpanExporter filtered = new GenAiOnlySpanExporter(otlp);
        log.info("Langfuse OTLP export enabled: {} (agent/gen_ai spans only)", endpoint);
        return builder -> builder.addSpanProcessor(BatchSpanProcessor.builder(filtered)
                .setScheduleDelay(2, TimeUnit.SECONDS)
                .setMaxQueueSize(8192)
                .setMaxExportBatchSize(512)
                .build());
    }

    /** 仅保留 AgentScope 埋点或携带 GenAI 语义属性的 span。 */
    static final class GenAiOnlySpanExporter implements SpanExporter {

        private final SpanExporter delegate;

        GenAiOnlySpanExporter(SpanExporter delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            List<SpanData> selected = new ArrayList<>(spans.size());
            for (SpanData span : spans) {
                if (isGenAiSpan(span)) {
                    selected.add(span);
                }
            }
            if (selected.isEmpty()) {
                return CompletableResultCode.ofSuccess();
            }
            return delegate.export(selected);
        }

        static boolean isGenAiSpan(SpanData span) {
            String scope = span.getInstrumentationScopeInfo().getName();
            if (scope != null && (scope.startsWith("agentscope") || scope.startsWith("langfuse"))) {
                return true;
            }
            if (hasGenAiAttribute(span.getAttributes().asMap().keySet())) {
                return true;
            }
            for (EventData event : span.getEvents()) {
                if (hasGenAiAttribute(event.getAttributes().asMap().keySet())) {
                    return true;
                }
            }
            return false;
        }

        private static boolean hasGenAiAttribute(Collection<AttributeKey<?>> keys) {
            for (AttributeKey<?> key : keys) {
                String name = key.getKey();
                if (name.startsWith("gen_ai.") || name.startsWith("agentscope.")) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public CompletableResultCode flush() {
            return delegate.flush();
        }

        @Override
        public CompletableResultCode shutdown() {
            return delegate.shutdown();
        }
    }
}
