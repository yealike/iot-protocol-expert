package top.yealike.pep.runtime.execution.domain;

import io.agentscope.extensions.model.openai.exception.OpenAIException;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/** 只暴露已识别的模型网关额度错误，供应商响应正文保持私有。 */
public record ModelQuotaFailure(String code, String message) {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, String> MESSAGES = Map.of(
            "QUOTA_EXHAUSTED", "当前模型额度不足或未分配，请联系管理员分配额度",
            "QUOTA_TOTAL_EXHAUSTED", "模型总量额度已耗尽或已过期，请联系管理员补充额度");

    public static Optional<ModelQuotaFailure> from(Throwable failure) {
        // SDK/reactor may wrap the HTTP exception; bound traversal for malformed cause chains.
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (!(failure instanceof OpenAIException http)
                    || !Integer.valueOf(429).equals(http.getStatusCode())) {
                continue;
            }
            String body = http.getResponseBody();
            if (body == null || body.length() > 8192) continue;
            try {
                var root = JSON.readTree(body);
                String key = root.path("code").isTextual()
                        ? root.path("code").textValue()
                        : root.path("error").isTextual()
                                ? root.path("error").textValue()
                                : root.path("error").path("code").isTextual()
                                        ? root.path("error").path("code").textValue()
                                        : null;
                if (key != null && MESSAGES.containsKey(key)) {
                    return Optional.of(new ModelQuotaFailure(key, MESSAGES.get(key)));
                }
            } catch (RuntimeException ignored) {
                // Unknown responses keep the generic execution error.
            }
        }
        return Optional.empty();
    }
}
