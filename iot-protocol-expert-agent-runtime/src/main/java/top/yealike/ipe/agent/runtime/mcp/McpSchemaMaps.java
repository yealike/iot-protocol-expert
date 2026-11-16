package top.yealike.ipe.agent.runtime.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.Map;

/** 把 MCP SDK 的 JsonSchema 转回规范 Map；摘要只覆盖模型可见字段。 */
public final class McpSchemaMaps {

    private McpSchemaMaps() {}

    public static Map<String, Object> inputSchema(McpSchema.Tool tool) {
        Map<String, Object> schema = new LinkedHashMap<>();
        McpSchema.JsonSchema json = tool.inputSchema();
        if (json == null) {
            schema.put("type", "object");
            schema.put("properties", Map.of());
            return schema;
        }
        schema.put("type", json.type() == null ? "object" : json.type());
        schema.put("properties", json.properties() == null ? Map.of() : json.properties());
        if (json.required() != null) {
            schema.put("required", json.required());
        }
        if (json.additionalProperties() != null) {
            schema.put("additionalProperties", json.additionalProperties());
        }
        if (json.defs() != null && !json.defs().isEmpty()) {
            schema.put("$defs", json.defs());
        }
        if (json.definitions() != null && !json.definitions().isEmpty()) {
            schema.put("definitions", json.definitions());
        }
        return schema;
    }

    public static Map<String, Object> annotations(McpSchema.Tool tool) {
        Map<String, Object> annotation = new LinkedHashMap<>();
        McpSchema.ToolAnnotations value = tool.annotations();
        if (value == null) {
            return annotation;
        }
        if (value.title() != null) {
            annotation.put("title", value.title());
        }
        put(annotation, "readOnlyHint", value.readOnlyHint());
        put(annotation, "destructiveHint", value.destructiveHint());
        put(annotation, "idempotentHint", value.idempotentHint());
        put(annotation, "openWorldHint", value.openWorldHint());
        return annotation;
    }

    private static void put(Map<String, Object> target, String key, Boolean value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
