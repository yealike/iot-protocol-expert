package top.yealike.pep.runtime.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.yealike.pep.runtime.TestFixtures;
import top.yealike.pep.runtime.config.properties.OtelProperties;

class McpConnectionPoolTraceTest {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();

    @AfterEach
    void closeProvider() {
        provider.close();
    }

    @Test
    void removesInvocationContextFromTraceWithoutChangingMcpRequest() {
        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.CallToolResult result = mock(McpSchema.CallToolResult.class);
        when(result.content()).thenReturn(List.of());
        when(result.structuredContent()).thenReturn(Map.of());
        when(result.isError()).thenReturn(false);
        when(client.callTool(any())).thenReturn(result);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        McpConnectionPool pool = new McpConnectionPool(
                TestFixtures.properties("http://127.0.0.1:19083", "test-secret", new OtelProperties(true, true, 8192)),
                new SimpleMeterRegistry(),
                executor);
        McpConnection connection = new McpConnection("ops|1.0.0", TestFixtures.mcpDescriptor(), client);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("order_no", "202411130001");
        arguments.put("_ipe_context", "sealed-context");

        try {
            var span = provider.get("test").spanBuilder("mcp call").startSpan();
            try (Scope ignored = span.makeCurrent()) {
                pool.call(connection, "get_order_detail", arguments);
            }
            span.end();

            ArgumentCaptor<McpSchema.CallToolRequest> request =
                    ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
            verify(client).callTool(request.capture());
            assertThat(request.getValue().arguments()).containsEntry("_ipe_context", "sealed-context");

            var requestEvent = exporter.getFinishedSpanItems().getFirst().getEvents().stream()
                    .filter(event -> "mcp.request".equals(event.getName()))
                    .findFirst()
                    .orElseThrow();
            String tracedArguments =
                    requestEvent.getAttributes().get(AttributeKey.stringKey("gen_ai.tool.call.arguments"));
            assertThat(tracedArguments)
                    .contains("202411130001")
                    .doesNotContain("_ipe_context")
                    .doesNotContain("sealed-context");
        } finally {
            pool.close();
            executor.shutdownNow();
        }
    }
}
