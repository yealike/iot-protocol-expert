package top.yealike.pep.runtime.contract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 输入 Schema 的规范化。Control 从目录导入与 Runtime 从 SDK 读取的 Schema 结构可能携带不同附加字段，
 * 摘要只覆盖模型真正看到的 type/properties/required/additionalProperties，避免无关元数据造成假漂移。
 */
public final class McpToolSchemas {

    private McpToolSchemas() {}

    public static String digest(Map<String, Object> inputSchema) {
        return Digests.digest(canonical(inputSchema));
    }

    public static Map<String, Object> canonical(Map<String, Object> inputSchema) {
        Map<String, Object> source = inputSchema == null ? Map.of() : inputSchema;
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("type", source.getOrDefault("type", "object"));
        canonical.put("properties", source.getOrDefault("properties", Map.of()));
        Object required = source.get("required");
        canonical.put("required", required instanceof List<?> list ? new ArrayList<>(list) : List.of());
        if (source.get("additionalProperties") != null) {
            canonical.put("additionalProperties", source.get("additionalProperties"));
        }
        return canonical;
    }
}
