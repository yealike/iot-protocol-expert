package top.yealike.pep.simulator.runtime;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import top.yealike.pep.simulator.config.SimulatorProperties;

/** 从运维平台只读加载可模拟设备，选择请求只提交平台设备 ID。 */
@Component
public class OpsDeviceClient {

    /** 平台枪号到第三方协议枪口的只读映射。 */
    public record SimulatorConnector(String platformGunNo, String protocolConnectorNo) {}

    public record RateModel(
            String modelNo,
            BigDecimal sharpEnergy,
            BigDecimal sharpService,
            BigDecimal peakEnergy,
            BigDecimal peakService,
            BigDecimal flatEnergy,
            BigDecimal flatService,
            BigDecimal valleyEnergy,
            BigDecimal valleyService,
            int lossRatio,
            List<Integer> segments,
            boolean active) {}

    public record SimulatorDevice(
            Long id,
            String sn,
            String name,
            String protocol,
            String protocolDeviceId,
            int gunCount,
            boolean online,
            String state,
            boolean enabled,
            boolean allowSimulate,
            List<SimulatorConnector> connectors) {

        public SimulatorDevice {
            connectors = connectors == null
                    ? List.of()
                    : connectors.stream().filter(Objects::nonNull).toList();
        }

        public SimulatorDevice(
                Long id,
                String sn,
                String name,
                String protocol,
                String protocolDeviceId,
                int gunCount,
                boolean online,
                String state,
                boolean enabled,
                boolean allowSimulate) {
            this(id, sn, name, protocol, protocolDeviceId, gunCount, online, state, enabled, allowSimulate, List.of());
        }
    }

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

    public List<RateModel> rates() {
        RateModel[] models = client.get()
                .uri("/api/ops/simulator/rate-models")
                .header("X-Ops-Simulator-Token", token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(RateModel[].class);
        return models == null ? List.of() : List.of(models);
    }

    private static org.springframework.http.client.ClientHttpRequestFactory requestFactory(int timeoutMs) {
        var settings = org.springframework.boot.http.client.HttpClientSettings.defaults()
                .withTimeouts(Duration.ofMillis(Math.max(timeoutMs, 500)), Duration.ofMillis(Math.max(timeoutMs, 500)));
        return org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder.detect()
                .build(settings);
    }
}
