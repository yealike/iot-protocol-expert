package top.yealike.ipe.ops.diagnostic;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import top.yealike.ipe.ops.config.OpsProperties;

/** 可选的模型摘要。端点不可用时返回空，页面继续展示确定性解析与证据时间线。 */
@Component
public class ModelSummaryService {

    private final OpsProperties properties;
    private final RestClient client;

    public ModelSummaryService(OpsProperties properties) {
        this.properties = properties;
        this.client = RestClient.builder()
                .requestFactory(requestFactory(properties.getModel().getTimeoutMs()))
                .build();
    }

    public Optional<String> summarize(String question, Object facts) {
        OpsProperties.Model model = properties.getModel();
        if (!model.isEnabled()
                || model.getBaseUrl() == null
                || model.getBaseUrl().isBlank()) {
            return Optional.empty();
        }
        try {
            String url = model.getBaseUrl().replaceAll("/$", "") + "/chat/completions";
            Map<String, Object> body = Map.of(
                    "model", model.getName(),
                    "temperature", 0,
                    "messages",
                            List.of(
                                    Map.of(
                                            "role",
                                            "system",
                                            "content",
                                            "你是充电桩协议排障助手。只能依据给定事实回答，明确区分已确认、证据不足和查询失败，不得编造。"),
                                    Map.of(
                                            "role",
                                            "user",
                                            "content",
                                            "问题：" + (question == null ? "解释排障结论" : question) + "\n事实：" + facts)));
            Map<?, ?> response = client.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + model.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null) return Optional.empty();
            Object choices = response.get("choices");
            if (!(choices instanceof List<?> list) || list.isEmpty()) return Optional.empty();
            Object first = list.get(0);
            if (!(first instanceof Map<?, ?> choice)) return Optional.empty();
            Object message = choice.get("message");
            if (!(message instanceof Map<?, ?> msg)) return Optional.empty();
            Object content = msg.get("content");
            return content == null ? Optional.empty() : Optional.of(content.toString());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static org.springframework.http.client.ClientHttpRequestFactory requestFactory(int timeoutMs) {
        var settings = org.springframework.boot.http.client.HttpClientSettings.defaults()
                .withTimeouts(
                        Duration.ofMillis(Math.max(timeoutMs, 1000)), Duration.ofMillis(Math.max(timeoutMs, 1000)));
        return org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder.detect()
                .build(settings);
    }
}
