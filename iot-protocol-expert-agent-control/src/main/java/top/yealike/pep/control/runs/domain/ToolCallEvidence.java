package top.yealike.pep.control.runs.domain;

import java.util.Map;
import top.yealike.pep.control.support.JsonSupport;

/** 工具参数或结果的脱敏摘要。 */
public record ToolCallEvidence(String text, boolean truncated) {

    public static ToolCallEvidence from(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        Object rawText = values.get("text");
        String text = rawText instanceof String value ? value : JsonSupport.write(values);
        return new ToolCallEvidence(text, Boolean.TRUE.equals(values.get("truncated")));
    }
}
