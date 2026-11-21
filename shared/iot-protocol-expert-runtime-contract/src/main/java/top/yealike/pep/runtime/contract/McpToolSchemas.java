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

    /** 校验实时输入结构，兼容已发布摘要保留 required 原始排序的版本。 */
    public static boolean matchesFrozen(Map<String, Object> live, McpToolDescriptor frozen) {
        if (digest(live).equals(frozen.schemaDigest())) {
            return true;
        }
        return digest(frozen.inputSchema()).equals(frozen.schemaDigest())
                && Digests.canonicalJson(withSortedRequired(live))
                        .equals(Digests.canonicalJson(withSortedRequired(frozen.inputSchema())));
    }

    private static Map<String, Object> withSortedRequired(Map<String, Object> inputSchema) {
        Map<String, Object> schema = canonical(inputSchema);
        if (schema.get("required") instanceof List<?> required
                && required.stream().allMatch(String.class::isInstance)) {
            schema.put(
                    "required",
                    required.stream().map(String.class::cast).sorted().toList());
        }
        return schema;
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
