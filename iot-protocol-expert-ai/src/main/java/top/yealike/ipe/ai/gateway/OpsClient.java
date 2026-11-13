package top.yealike.ipe.ai.gateway;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import top.yealike.ipe.ai.config.AiProperties;
import top.yealike.ipe.ai.identity.UserIdentity;
import top.yealike.ipe.ai.support.AiExceptions;
import top.yealike.ipe.ai.support.JsonSupport;

/**
 * ops 内部只读网关。
 *
 * <p>AI 服务不直接读写业务表；所有业务数据都经这里取，ops 会再用站点范围做一次校验。
 */
@Component
public class OpsClient {

    private static final Logger log = LoggerFactory.getLogger(OpsClient.class);

    public static final String HEADER_SERVICE_TOKEN = "X-Ops-Service-Token";
    public static final String HEADER_ACTOR = "X-Ai-Actor";
    public static final String HEADER_STATION_SCOPE = "X-Ai-Station-Scope";

    private final RestClient client;
    private final AiProperties properties;

    public OpsClient(AiProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.client = builder.baseUrl(properties.opsBaseUrl()).build();
    }

    public String serviceToken() {
        return properties.serviceToken();
    }

    public record Envelope(
            Map<String, Object> data, String dataTime, String source, boolean partial, List<String> warnings) {

        public static Envelope error(String warning) {
            return new Envelope(Map.of(), null, "AI", true, List.of(warning));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new java.util.LinkedHashMap<>();
            value.put("data", data);
            value.put("data_time", dataTime);
            value.put("source", source);
            value.put("partial", partial);
            value.put("warnings", warnings);
            return value;
        }
    }

    public Envelope orderDetail(UserIdentity identity, String scope, String orderNo) {
        return get(identity, scope, "/internal/ai/orders/" + encode(orderNo));
    }

    public Envelope orderBills(UserIdentity identity, String scope, String orderNo) {
        return get(identity, scope, "/internal/ai/orders/" + encode(orderNo) + "/bills");
    }

    public Envelope deviceDetail(UserIdentity identity, String scope, long deviceId) {
        return get(identity, scope, "/internal/ai/devices/" + deviceId);
    }

    public Envelope telemetrySummary(UserIdentity identity, String scope, String orderNo, Integer fromHours) {
        String path = "/internal/ai/orders/" + encode(orderNo) + "/telemetry-summary";
        if (fromHours != null && fromHours > 0) {
            path = path + "?window_hours=" + fromHours;
        }
        return get(identity, scope, path);
    }

    public List<Map<String, Object>> documents(UserIdentity identity) {
        String body = client.get()
                .uri("/internal/ai/documents")
                .header(HEADER_SERVICE_TOKEN, properties.serviceToken())
                .header(HEADER_ACTOR, identity.actor())
                .retrieve()
                .body(String.class);
        Map<String, Object> parsed = JsonSupport.readMap(body);
        Object data = parsed.get("data");
        if (data instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    public byte[] documentContent(UserIdentity identity, String documentId) {
        try {
            return client.get()
                    .uri("/internal/ai/documents/{id}/content", documentId)
                    .header(HEADER_SERVICE_TOKEN, properties.serviceToken())
                    .header(HEADER_ACTOR, identity.actor())
                    .accept(MediaType.APPLICATION_OCTET_STREAM)
                    .retrieve()
                    .body(byte[].class);
        } catch (RuntimeException exception) {
            log.warn("load protocol document failed: id={}", documentId, exception);
            throw AiExceptions.upstream("协议文档读取失败");
        }
    }

    private Envelope get(UserIdentity identity, String scope, String path) {
        try {
            String body = client.get()
                    .uri(path)
                    .header(HEADER_SERVICE_TOKEN, properties.serviceToken())
                    .header(HEADER_ACTOR, identity.actor())
                    .header(HEADER_STATION_SCOPE, scope == null ? "" : scope)
                    .retrieve()
                    .body(String.class);
            Map<String, Object> parsed = JsonSupport.readMap(body);
            Object data = parsed.get("data");
            List<String> warnings = parsed.get("warnings") instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : List.of();
            return new Envelope(
                    data instanceof Map ? (Map<String, Object>) data : Map.of(),
                    parsed.get("data_time") == null ? null : String.valueOf(parsed.get("data_time")),
                    parsed.get("source") == null ? "OPS" : String.valueOf(parsed.get("source")),
                    Boolean.TRUE.equals(parsed.get("partial")),
                    warnings);
        } catch (org.springframework.web.client.RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 403) {
                return Envelope.error("无权访问该订单或设备所属站点");
            }
            if (status == 404) {
                return Envelope.error("未找到对应数据");
            }
            log.warn("ops internal call failed: path={} status={}", path, status);
            return Envelope.error("业务服务暂时不可用，未能获取数据");
        } catch (RuntimeException exception) {
            log.warn("ops internal call failed: path={}", path, exception);
            return Envelope.error("业务服务暂时不可用，未能获取数据");
        }
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    public Duration timeout() {
        return Duration.ofMillis(properties.requestTimeoutMs());
    }
}
