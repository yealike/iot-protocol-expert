package top.yealike.pep.modelgateway.chat;

import java.util.Map;
import org.springframework.stereotype.Component;
import top.yealike.pep.model.contract.ModelUsage;

/**
 * 供应商 usage 归一化。
 *
 * <p>输入包含缓存命中与工具结果；缓存明细不重复相加。reasoning 已包含在供应商输出计数中，
 * 默认只记录明细，不再追加到 quota_units。
 */
@Component
public class UsageNormalizer {

    public ModelUsage normalize(Map<String, Object> usage, int weightNum, int weightDen) {
        if (usage == null || usage.isEmpty()) {
            return null;
        }
        long input = longValue(first(usage, "prompt_tokens", "input_tokens"));
        long output = longValue(first(usage, "completion_tokens", "output_tokens"));
        long cacheRead = detail(usage, "prompt_tokens_details", "cached_tokens");
        long cacheWrite = detail(usage, "prompt_tokens_details", "cache_write_tokens");
        long reasoning = detail(usage, "completion_tokens_details", "reasoning_tokens");
        long units = TokenEstimator.weight(input + output, weightNum, weightDen);
        return new ModelUsage(input, output, cacheRead, cacheWrite, reasoning, units, "PROVIDER");
    }

    public ModelUsage estimatedCap(long reservedUnits) {
        return new ModelUsage(0, reservedUnits, 0, 0, 0, reservedUnits, "ESTIMATED_CAP");
    }

    private static Object first(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static long detail(Map<String, Object> map, String detailKey, String valueKey) {
        if (map.get(detailKey) instanceof Map<?, ?> detail) {
            Object value = detail.get(valueKey);
            return longValue(value);
        }
        return 0;
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return Math.max(0, number.longValue());
        }
        if (value instanceof String text) {
            try {
                return Math.max(0, Long.parseLong(text));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
