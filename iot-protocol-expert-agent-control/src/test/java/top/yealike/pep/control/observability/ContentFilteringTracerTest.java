package top.yealike.pep.control.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.yealike.pep.observability.ContentFilteringTracer;

class ContentFilteringTracerTest {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
    private final Tracer delegate = provider.get("test");

    @AfterEach
    void closeProvider() {
        provider.close();
    }

    @Test
    void dropsContentButKeepsTokenUsage() {
        var tracer = new ContentFilteringTracer(delegate, false, 8192);

        var span = tracer.spanBuilder("chat fake-model").startSpan();
        span.setAttribute("gen_ai.input.messages", "{\"role\":\"user\",\"content\":\"订单 202411130001\"}");
        span.setAttribute("agentscope.function.input", "{\"messages\":[]}");
        span.setAttribute("gen_ai.usage.input_tokens", 12L);
        span.setAttribute("gen_ai.usage.output_tokens", 34L);
        span.end();

        var exported = exporter.getFinishedSpanItems().get(0);
        assertThat(exported.getAttributes().get(AttributeKey.stringKey("gen_ai.input.messages")))
                .isNull();
        assertThat(exported.getAttributes().get(AttributeKey.stringKey("agentscope.function.input")))
                .isNull();
        assertThat(exported.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")))
                .isEqualTo(12L);
        assertThat(exported.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens")))
                .isEqualTo(34L);
    }

    @Test
    void keepsRedactedAndTruncatedContentWhenEnabled() {
        var tracer = new ContentFilteringTracer(delegate, true, 60);
        String content = "{\"api_key\":\"sk-1234567890abcdef\",\"text\":\"" + "x".repeat(200) + "\"}";

        var span = tracer.spanBuilder("chat fake-model").startSpan();
        span.setAttribute("gen_ai.input.messages", content);
        span.end();

        String exported = exporter.getFinishedSpanItems()
                .get(0)
                .getAttributes()
                .get(AttributeKey.stringKey("gen_ai.input.messages"));
        assertThat(exported).doesNotContain("sk-1234567890abcdef");
        assertThat(exported).contains("***");
        assertThat(exported).contains("[truncated]");
        assertThat(exported).hasSize(60);
    }

    @Test
    void filtersContentFromEventsExceptionsAndLinks() {
        var tracer = new ContentFilteringTracer(delegate, false, 8192);
        var span = tracer.spanBuilder("tool call").startSpan();
        var remote = SpanContext.createFromRemoteParent(
                "0123456789abcdef0123456789abcdef",
                "0123456789abcdef",
                TraceFlags.getSampled(),
                TraceState.getDefault());

        span.addEvent(
                "mcp.request",
                Attributes.builder()
                        .put("gen_ai.tool.name", "get_device")
                        .put("gen_ai.tool.call.arguments", "{\"password\":\"secret\"}")
                        .build());
        span.recordException(
                new IllegalStateException("boom"),
                Attributes.builder()
                        .put("error.type", "IllegalStateException")
                        .put("gen_ai.tool.call.result", "secret result")
                        .build());
        span.addLink(
                remote,
                Attributes.builder()
                        .put("link.kind", "verification")
                        .put("gen_ai.tool.call.arguments", "{\"token\":\"secret\"}")
                        .build());
        span.end();

        var exported = exporter.getFinishedSpanItems().get(0);
        var request = exported.getEvents().stream()
                .filter(event -> "mcp.request".equals(event.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(request.getAttributes().get(AttributeKey.stringKey("gen_ai.tool.name")))
                .isEqualTo("get_device");
        assertThat(request.getAttributes().get(AttributeKey.stringKey("gen_ai.tool.call.arguments")))
                .isNull();

        var exception = exported.getEvents().stream()
                .filter(event -> "exception".equals(event.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(exception.getAttributes().get(AttributeKey.stringKey("error.type")))
                .isEqualTo("IllegalStateException");
        assertThat(exception.getAttributes().get(AttributeKey.stringKey("gen_ai.tool.call.result")))
                .isNull();

        var link = exported.getLinks().get(0);
        assertThat(link.getAttributes().get(AttributeKey.stringKey("link.kind")))
                .isEqualTo("verification");
        assertThat(link.getAttributes().get(AttributeKey.stringKey("gen_ai.tool.call.arguments")))
                .isNull();
    }
}
