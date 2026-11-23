package top.yealike.pep.control.agents.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/** 原生 MCP 目录的稳定定位信息。 */
public record McpNativeRegistryRef(String serverName, String namespace, String version) {

    public static McpNativeRegistryRef from(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return new McpNativeRegistryRef(
                text(values.get("serverName")), text(values.get("namespace")), text(values.get("version")));
    }

    public Map<String, Object> asMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        if (serverName != null) {
            values.put("serverName", serverName);
        }
        if (namespace != null) {
            values.put("namespace", namespace);
        }
        if (version != null) {
            values.put("version", version);
        }
        return Map.copyOf(values);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).strip();
    }
}
