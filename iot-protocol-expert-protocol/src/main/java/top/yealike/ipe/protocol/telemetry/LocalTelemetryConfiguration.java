package top.yealike.ipe.protocol.telemetry;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SdkTracerProviderBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Bounded, local-profile-only viewer attached to Boot's SDK; no second SDK or agent. */
@Profile("local")
@Configuration(proxyBeanMethods = false)
public class LocalTelemetryConfiguration {
    @Bean
    RecentSpans recentSpans() {
        return new RecentSpans();
    }

    @Bean
    SdkTracerProviderBuilderCustomizer localTraceViewer(RecentSpans spans) {
        // Keep this exporter inside the customizer. An exporter bean would also be
        // auto-registered by Boot's batch processor and deliver every span twice.
        return builder -> builder.addSpanProcessor(SimpleSpanProcessor.create(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> batch) {
                spans.append(batch);
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        }));
    }

    @Bean
    TelemetryEndpoint telemetryEndpoint(RecentSpans spans) {
        return new TelemetryEndpoint(spans);
    }

    static final class RecentSpans {
        private final ArrayDeque<SpanData> spans = new ArrayDeque<>();

        synchronized void append(Collection<SpanData> batch) {
            for (SpanData span : batch) {
                if (spans.size() == 1000) spans.removeFirst();
                spans.addLast(span);
            }
        }

        synchronized List<Map<String, Object>> snapshot(@Nullable String traceId) {
            return spans.reversed().stream()
                    .filter(s -> traceId == null || s.getTraceId().equals(traceId))
                    .limit(200)
                    .map(s -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("traceId", s.getTraceId());
                        row.put("spanId", s.getSpanId());
                        row.put("parentSpanId", s.getParentSpanId());
                        row.put("name", s.getName());
                        row.put("kind", s.getKind().name());
                        row.put("status", s.getStatus().getStatusCode().name());
                        row.put("durationMs", (s.getEndEpochNanos() - s.getStartEpochNanos()) / 1_000_000.0);
                        Map<String, Object> tags = new LinkedHashMap<>();
                        s.getAttributes().forEach((key, value) -> tags.put(key.getKey(), value));
                        row.put("tags", tags);
                        return row;
                    })
                    .toList();
        }
    }

    @Endpoint(id = "telemetry")
    public static final class TelemetryEndpoint {
        private final RecentSpans spans;

        TelemetryEndpoint(RecentSpans spans) {
            this.spans = spans;
        }

        @ReadOperation
        public Map<String, Object> read(@Nullable String traceId) {
            return Map.of("mode", "local", "capacity", 1000, "spans", spans.snapshot(traceId));
        }
    }
}
