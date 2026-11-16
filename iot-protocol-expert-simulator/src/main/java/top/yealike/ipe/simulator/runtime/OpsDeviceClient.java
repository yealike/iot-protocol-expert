package top.yealike.ipe.simulator.runtime;

import java.time.Duration;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import top.yealike.ipe.simulator.config.SimulatorProperties;

/** 从运维平台只读加载可模拟设备，选择请求只提交平台设备 ID。 */
@Component
public class OpsDeviceClient {

    public record SimulatorDevice(
            Long id,
            String sn,
            Long stationId,
            String stationName,
            String protocol,
            String protocolDeviceId,
            int gunCount,
            boolean online,
            boolean enabled,
            boolean allowSimulate) {}

    private final RestClient client;
    private final String token;

    public OpsDeviceClient(RestClient.Builder builder, SimulatorProperties properties) {
        this.token = properties.getOpsSimulatorToken();
        this.client = builder.baseUrl(properties.getOpsBaseUrl())
                .requestFactory(requestFactory(properties.getOpsTimeoutMs()))
                .build();
    }

    public List<SimulatorDevice> list() {
        SimulatorDevice[] devices = client.get()
                .uri("/api/ops/simulator/devices")
                .header("X-Ops-Simulator-Token", token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(SimulatorDevice[].class);
        return devices == null ? List.of() : List.of(devices);
    }

    public SimulatorDevice get(Long id) {
        return client.get()
                .uri("/api/ops/simulator/devices/{id}", id)
                .header("X-Ops-Simulator-Token", token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(SimulatorDevice.class);
    }

    private static org.springframework.http.client.ClientHttpRequestFactory requestFactory(int timeoutMs) {
        var settings = org.springframework.boot.http.client.HttpClientSettings.defaults()
                .withTimeouts(Duration.ofMillis(Math.max(timeoutMs, 500)), Duration.ofMillis(Math.max(timeoutMs, 500)));
        return org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder.detect()
                .build(settings);
    }
}
