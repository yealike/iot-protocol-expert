package top.yealike.pep.runtime.prepare.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import top.yealike.pep.runtime.contract.McpToolDescriptor;

/** 已冻结的模型可见工具：alias 是唯一模型身份，originalName 才发给 Provider。 */
public record PreparedTool(
        String alias, String mcpKey, String mcpVersion, String originalName, McpToolDescriptor descriptor) {

    public Map<String, Object> parametersForModel() {
        Map<String, Object> source = descriptor.inputSchema() == null ? Map.of() : descriptor.inputSchema();
        Map<String, Object> parameters = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!"properties".equals(key) && !"required".equals(key)) {
                parameters.put(key, value);
            }
        });
        Object properties = source.get("properties");
        if (properties instanceof Map<?, ?> map) {
            Map<String, Object> fields = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (!"_ipe_context".equals(String.valueOf(key))) {
                    fields.put(String.valueOf(key), value);
                }
            });
            parameters.put("properties", fields);
        } else {
            parameters.put("properties", Map.of());
        }
        if (source.get("required") instanceof List<?> required) {
            List<String> filtered = new ArrayList<>();
            required.stream()
                    .map(String::valueOf)
                    .filter(name -> !"_ipe_context".equals(name))
                    .forEach(filtered::add);
            parameters.put("required", filtered);
        } else {
            parameters.put("required", List.of());
        }
        return Map.copyOf(parameters);
    }
}
