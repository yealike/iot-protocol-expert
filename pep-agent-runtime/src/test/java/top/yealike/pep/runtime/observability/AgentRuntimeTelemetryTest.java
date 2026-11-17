package top.yealike.pep.runtime.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tracing.TracerRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import top.yealike.pep.runtime.TestFixtures;
import top.yealike.pep.runtime.config.RuntimeProperties;

class AgentRuntimeTelemetryTest {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
    private final OpenTelemetry openTelemetry =
            OpenTelemetrySdk.builder().setTracerProvider(provider).build();

    @AfterEach
    void tearDown() {
        TracerRegistry.resetToNoop();
        provider.close();
    }

    @Test
    void runtimeRegistersAgentAndModelSpansWithoutContentByDefault() {
        AgentRuntimeTelemetry telemetry = new AgentRuntimeTelemetry(
                openTelemetry,
                TestFixtures.properties(
                        "http://127.0.0.1:19083", "test-secret", new RuntimeProperties.Otel(true, false, 8192)));
        telemetry.afterPropertiesSet();

        ReActAgent agent = ReActAgent.builder()
                .name("protocol-expert")
                .sysPrompt("you are a protocol expert")
                .model(new FakeChatModel())
                .maxIters(1)
                .build();
        var runSpan =
                openTelemetry.getTracer("test").spanBuilder("ai run execute").startSpan();
        try (var ignored = runSpan.makeCurrent()) {
            agent.streamEvents(List.of(new UserMessage("订单 202411130001 为什么停止？")))
                    .blockLast();
        }
        runSpan.end();
        agent.close();

        var spans = exporter.getFinishedSpanItems();
        var agentSpan = spans.stream()
                .filter(item -> item.getName().equals("invoke_agent protocol-expert"))
                .findFirst()
                .orElseThrow();
        var chatSpan = spans.stream()
                .filter(item -> item.getName().equals("chat fake-model"))
                .findFirst()
                .orElseThrow();

        assertThat(agentSpan.getTraceId()).isEqualTo(runSpan.getSpanContext().getTraceId());
        assertThat(agentSpan.getParentSpanId())
                .isEqualTo(runSpan.getSpanContext().getSpanId());
        assertThat(chatSpan.getParentSpanId())
                .isEqualTo(agentSpan.getSpanContext().getSpanId());
        assertThat(chatSpan.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")))
                .isEqualTo(11L);
        assertThat(chatSpan.getAttributes().get(AttributeKey.stringKey("gen_ai.input.messages")))
                .isNull();
        assertThat(chatSpan.getAttributes().get(AttributeKey.stringKey("gen_ai.output.messages")))
                .isNull();
    }

    @Test
    void runtimeExportsContentOnlyWhenExplicitlyEnabled() {
        AgentRuntimeTelemetry telemetry = new AgentRuntimeTelemetry(
                openTelemetry,
                TestFixtures.properties(
                        "http://127.0.0.1:19083", "test-secret", new RuntimeProperties.Otel(true, true, 4096)));
        telemetry.afterPropertiesSet();

        new FakeChatModel()
                .stream(
                                List.of(new UserMessage("订单 202411130001 为什么停止？")),
                                List.of(),
                                GenerateOptions.builder().build())
                        .collectList()
                        .block();

        var span = exporter.getFinishedSpanItems().stream()
                .filter(item -> item.getName().equals("chat fake-model"))
                .findFirst()
                .orElseThrow();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.input.messages")))
                .contains("订单 202411130001");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.output.messages")))
                .contains("停止原因");
    }

    private static final class FakeChatModel extends ChatModelBase {

        @Override
        protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(ChatResponse.builder()
                    .id("response-1")
                    .content(List.of(TextBlock.builder().text("停止原因是达到 SOC 上限。").build()))
                    .usage(new ChatUsage(11, 7, 0.01))
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "fake-model";
        }
    }
}
