package top.yealike.ipe.ops.service;

import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import top.yealike.ipe.ops.config.OpsProperties;
import top.yealike.ipe.ops.dto.OpsDtos;

/** 运维平台到协议通道的内部调用。有限超时，失败时命令记录保留未知状态。 */
@Component
public class ProtocolClient {

    private final RestClient client;

    public ProtocolClient(RestClient.Builder builder, OpsProperties properties) {
        this.client = builder.baseUrl(properties.getProtocolBaseUrl())
                .requestFactory(requestFactory(properties.getProtocolTimeoutMs()))
                .build();
    }

    public OpsDtos.CommandResult execute(OpsDtos.CommandRequest request, String serviceToken) {
        OpsDtos.CommandResult result = client.post()
                .uri("/internal/commands")
                .header("X-Ops-Service-Token", serviceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(OpsDtos.CommandResult.class);
        return result == null ? new OpsDtos.CommandResult(false, "协议通道未返回结果", java.util.Map.of()) : result;
    }

    private static org.springframework.http.client.ClientHttpRequestFactory requestFactory(int timeoutMs) {
        var settings = org.springframework.boot.http.client.HttpClientSettings.defaults()
                .withTimeouts(
                        Duration.ofMillis(Math.max(timeoutMs, 1000)), Duration.ofMillis(Math.max(timeoutMs, 1000)));
        return org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder.detect()
                .build(settings);
    }
}
