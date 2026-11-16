package top.yealike.ipe.agent.runtime.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpToolEnvelopeTest {

    @Test
    void preservesStructuredContentContentAndErrorSemantics() {
        McpToolEnvelope envelope = new McpToolEnvelope(
                false,
                Map.of("status", 404),
                Map.of("status", 404),
                List.of(Map.of("type", "text", "text", "not found")),
                "MCP 工具返回错误",
                List.of("warning"),
                "ops",
                false,
                true);

        Map<String, Object> body = envelope.asMap();
        assertThat(body.get("ok")).isEqualTo(false);
        assertThat(body.get("isError")).isEqualTo(true);
        assertThat(body.get("structuredContent")).isEqualTo(Map.of("status", 404));
        assertThat(body.get("content").toString()).contains("not found");
        assertThat(body.get("source")).isEqualTo("ops");
        assertThat(body.get("error")).isEqualTo("MCP 工具返回错误");
    }

    @Test
    void failureHasStableEnvelopeShape() {
        Map<String, Object> body = McpToolEnvelope.failure("offline", "ops").asMap();
        assertThat(body)
                .containsEntry("ok", false)
                .containsEntry("isError", true)
                .containsEntry("source", "ops")
                .containsKey("data")
                .containsKey("warnings");
    }
}
