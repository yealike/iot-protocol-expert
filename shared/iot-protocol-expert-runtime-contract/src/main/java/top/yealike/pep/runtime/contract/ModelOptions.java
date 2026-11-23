package top.yealike.pep.runtime.contract;

import java.util.LinkedHashMap;
import java.util.Map;

/** Agent 与模型配置使用的固定调用参数。 */
public record ModelOptions(Double temperature, Integer maxTokens, Integer contextWindow) {

    public Map<String, Object> asMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        if (temperature != null) {
            values.put("temperature", temperature);
        }
        if (maxTokens != null) {
            values.put("maxTokens", maxTokens);
        }
        if (contextWindow != null) {
            values.put("contextWindow", contextWindow);
        }
        return Map.copyOf(values);
    }
}
