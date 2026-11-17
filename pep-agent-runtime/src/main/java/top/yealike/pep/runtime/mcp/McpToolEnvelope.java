package top.yealike.pep.runtime.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工具统一返回结构；不把原文直接写入事件或 OTel。 */
public record McpToolEnvelope(
        boolean ok,
        Object data,
        Object structuredContent,
        List<Map<String, Object>> content,
        String error,
        List<String> warnings,
        String source,
        boolean truncated,
        boolean isError) {

    public McpToolEnvelope {
        structuredContent = structuredContent == null ? Map.of() : structuredContent;
        content = content == null ? List.of() : List.copyOf(content);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        isError = isError || !ok;
    }

    public Map<String, Object> asMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("ok", ok);
        value.put("source", source);
        value.put("data", data);
        value.put("isError", isError);
        if (!(structuredContent instanceof Map<?, ?> map && map.isEmpty())) {
            value.put("structuredContent", structuredContent);
        }
        if (!content.isEmpty()) {
            value.put("content", content);
        }
        if (error != null) {
            value.put("error", error);
        }
        value.put("warnings", warnings);
        if (truncated) {
            value.put("truncated", true);
        }
        return value;
    }

    public static McpToolEnvelope failure(String message, String source) {
        return new McpToolEnvelope(
                false, Map.of(), Map.of(), List.of(), message, List.of(message), source, false, true);
    }
}
