package top.yealike.pep.control.agents.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.Map;
import top.yealike.pep.runtime.contract.ComponentDigests;
import top.yealike.pep.runtime.contract.McpToolDescriptor;

/** 把 MCP SDK 的工具模型映射为平台冻结描述，集中处理 Schema 与 annotations 的空值。 */
final class McpToolDescriptors {

    private McpToolDescriptors() {}

    static McpToolDescriptor describe(McpSchema.Tool tool) {
        Map<String, Object> input = inputSchema(tool.inputSchema());
        Map<String, Object> output = tool.outputSchema() == null ? Map.of() : new LinkedHashMap<>(tool.outputSchema());
        Map<String, Object> annotations = annotations(tool.annotations());
        return new McpToolDescriptor(
                tool.name(),
                tool.description() == null ? "" : tool.description(),
                input,
                output,
                annotations,
                ComponentDigests.mcpTool(input),
                Boolean.TRUE.equals(annotations.get("readOnlyHint")));
    }

    static Map<String, Object> inputSchema(McpSchema.JsonSchema schema) {
        if (schema == null) {
            return Map.of();
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", schema.type() == null ? "object" : schema.type());
        input.put("properties", schema.properties() == null ? Map.of() : schema.properties());
        if (schema.required() != null) {
            input.put("required", schema.required());
        }
        if (schema.additionalProperties() != null) {
            input.put("additionalProperties", schema.additionalProperties());
        }
        if (schema.defs() != null && !schema.defs().isEmpty()) {
            input.put("$defs", schema.defs());
        }
        if (schema.definitions() != null && !schema.definitions().isEmpty()) {
            input.put("definitions", schema.definitions());
        }
        return input;
    }

    static Map<String, Object> annotations(McpSchema.ToolAnnotations annotations) {
        if (annotations == null) {
            return Map.of();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (annotations.title() != null) {
            data.put("title", annotations.title());
        }
        put(data, "readOnlyHint", annotations.readOnlyHint());
        put(data, "destructiveHint", annotations.destructiveHint());
        put(data, "idempotentHint", annotations.idempotentHint());
        put(data, "openWorldHint", annotations.openWorldHint());
        return data;
    }

    private static void put(Map<String, Object> target, String key, Boolean value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
