package top.yealike.pep.control.agents.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** MCP 草稿中的固定连接参数。 */
public record McpDraft(String transport, String contextMode, Integer timeoutMs, UUID credentialId, String endpoint) {

    public static McpDraft from(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return new McpDraft(
                text(values.get("transport")),
                text(values.get("contextMode")),
                integer(values.get("timeoutMs")),
                uuid(values.get("credentialId")),
                text(values.get("endpoint")));
    }

    public Map<String, Object> asMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        if (transport != null) {
            values.put("transport", transport);
        }
        if (contextMode != null) {
            values.put("contextMode", contextMode);
        }
        if (timeoutMs != null) {
            values.put("timeoutMs", timeoutMs);
        }
        if (credentialId != null) {
            values.put("credentialId", credentialId.toString());
        }
        if (endpoint != null) {
            values.put("endpoint", endpoint);
        }
        return Map.copyOf(values);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).strip();
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String text && !text.isBlank()) {
            return UUID.fromString(text);
        }
        return null;
    }
}
