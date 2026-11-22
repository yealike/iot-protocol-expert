package top.yealike.pep.control.agents.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.yealike.pep.runtime.contract.ComponentDigests;
import top.yealike.pep.runtime.contract.McpToolDescriptor;

class McpToolDescriptorsTest {

    @Test
    void mapsInputSchemaOutputSchemaAndAnnotations() {
        McpSchema.Tool tool = new McpSchema.Tool(
                "list_devices",
                "List devices",
                "Lists devices",
                new McpSchema.JsonSchema(
                        "object",
                        Map.of("page", Map.of("type", "integer")),
                        List.of("page"),
                        false,
                        Map.of("Page", Map.of("type", "object")),
                        null),
                Map.of("type", "object"),
                new McpSchema.ToolAnnotations("List", true, false, true, false, null),
                null);

        McpToolDescriptor descriptor = McpToolDescriptors.describe(tool);

        assertThat(descriptor.name()).isEqualTo("list_devices");
        assertThat(descriptor.description()).isEqualTo("Lists devices");
        assertThat(descriptor.inputSchema())
                .containsEntry("type", "object")
                .containsEntry("properties", Map.of("page", Map.of("type", "integer")))
                .containsEntry("required", List.of("page"))
                .containsEntry("additionalProperties", false)
                .containsEntry("$defs", Map.of("Page", Map.of("type", "object")));
        assertThat(descriptor.outputSchema()).containsEntry("type", "object");
        assertThat(descriptor.annotations())
                .containsEntry("title", "List")
                .containsEntry("readOnlyHint", true)
                .containsEntry("destructiveHint", false)
                .containsEntry("idempotentHint", true)
                .containsEntry("openWorldHint", false);
        assertThat(descriptor.readOnly()).isTrue();
        assertThat(descriptor.schemaDigest()).isEqualTo(ComponentDigests.mcpTool(descriptor.inputSchema()));
    }

    @Test
    void handlesMissingSchemaAndAnnotations() {
        McpSchema.Tool tool = new McpSchema.Tool("ping", null, null, null, null, null, null);

        McpToolDescriptor descriptor = McpToolDescriptors.describe(tool);

        assertThat(descriptor.description()).isEmpty();
        assertThat(descriptor.inputSchema()).isEmpty();
        assertThat(descriptor.outputSchema()).isEmpty();
        assertThat(descriptor.annotations()).isEmpty();
        assertThat(descriptor.readOnly()).isFalse();
    }
}
