package top.yealike.pep.modelgateway.chat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import top.yealike.pep.modelgateway.config.GatewayProperties;

/**
 * 请求输入上界估算。
 *
 * <p>供应商未提供可信 tokenizer 时，使用“字符数 / chars-per-token + 固定开销”的保守估算。
 * 该值只用于预占上界，不写入最终用量；实际结算以供应商返回 usage 为准。
 */
@Component
public class TokenEstimator {

    private final GatewayProperties properties;
    private final ObjectMapper json;

    public TokenEstimator(GatewayProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    public long estimateInput(Map<String, Object> body) {
        String serialized = json.writeValueAsString(body.getOrDefault("messages", List.of()));
        long chars = serialized.length();
        Object tools = body.get("tools");
        if (tools != null) {
            chars += json.writeValueAsString(tools).length();
        }
        int messages = body.get("messages") instanceof List<?> list ? list.size() : 1;
        BigDecimal perToken = new BigDecimal(properties.getCounting().getCharsPerToken());
        if (perToken.compareTo(BigDecimal.ZERO) <= 0) {
            perToken = new BigDecimal("0.5");
        }
        long tokens =
                new BigDecimal(chars).divide(perToken, 0, RoundingMode.CEILING).longValue();
        return tokens
                + (long) messages * Math.max(0, properties.getCounting().getMessageOverhead())
                + Math.max(0, properties.getCounting().getRequestOverhead());
    }

    public long reserveUnits(
            Map<String, Object> body, int maxOutputTokens, int weightNumerator, int weightDenominator) {
        long input = estimateInput(body);
        long raw = input + Math.max(0, maxOutputTokens);
        int denominator = Math.max(1, weightDenominator);
        return ceilDiv(
                BigDecimal.valueOf(raw)
                        .multiply(BigDecimal.valueOf(Math.max(1, weightNumerator)))
                        .longValue(),
                denominator);
    }

    public static long weight(long rawUnits, int numerator, int denominator) {
        return ceilDiv(rawUnits * Math.max(1, numerator), Math.max(1, denominator));
    }

    private static long ceilDiv(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }
}
