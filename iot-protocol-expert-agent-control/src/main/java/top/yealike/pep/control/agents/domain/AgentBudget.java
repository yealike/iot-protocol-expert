package top.yealike.pep.control.agents.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/** Agent 运行预算，字段与 ai_agent_component.budget_json 保持一致。 */
public record AgentBudget(Integer maxIterations, Integer maxToolCalls, Long runTimeoutMs) {

    public static AgentBudget from(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return new AgentBudget(
                integer(values.get("maxIterations")),
                integer(values.get("maxToolCalls")),
                longValue(values.get("runTimeoutMs")));
    }

    public Map<String, Object> asMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        if (maxIterations != null) {
            values.put("maxIterations", maxIterations);
        }
        if (maxToolCalls != null) {
            values.put("maxToolCalls", maxToolCalls);
        }
        if (runTimeoutMs != null) {
            values.put("runTimeoutMs", runTimeoutMs);
        }
        return Map.copyOf(values);
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
