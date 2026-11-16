package top.yealike.ipe.protocol.collector;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import top.yealike.ipe.protocol.ops.OpsClient;

/** Exact-topic, explicitly mapped MQTT telemetry bridge; not a durable message queue. */
@Component
public class MqttCollector {
    private static final Logger log = LoggerFactory.getLogger(MqttCollector.class);
    private final OpsClient ops;
    private final ObjectMapper json;
    private final String path, username, password;
    private MqttAsyncClient client;
    private MqttConnectOptions options;
    private final Map<String, Device> topics = new HashMap<>();

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Config(Broker broker, List<Device> devices) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Broker(
            String host, Integer port, Boolean tls, String clientId, String caFile, String certFile, String keyFile) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Device(
            String topic, String protocolDeviceId, String protocol, String channelNo, Map<String, String> properties) {}

    public MqttCollector(
            OpsClient ops,
            ObjectMapper json,
            @Value("${IPE_MQTT_CONFIG:}") String path,
            @Value("${IPE_MQTT_USERNAME:}") String username,
            @Value("${IPE_MQTT_PASSWORD:}") String password) {
        this.ops = ops;
        this.json = json;
        this.path = path;
        this.username = username;
        this.password = password;
    }

    @PostConstruct
    public void start() throws Exception {
        if (path == null || path.isBlank()) return;
        Config config = json.readValue(Files.readString(Path.of(path)), Config.class);
        Broker b = config.broker();
        if (b == null
                || b.host() == null
                || b.clientId() == null
                || b.clientId().isBlank()
                || config.devices() == null) throw new IllegalArgumentException("MQTT 配置无效");
        boolean tls = b.tls() == null || b.tls();
        if (!tls && !SetLocal.isLoopback(b.host())) throw new IllegalArgumentException("明文 MQTT 只允许本机");
        if (b.caFile() != null || b.certFile() != null || b.keyFile() != null)
            throw new IllegalArgumentException("暂不支持 PEM 证书配置，请使用 JVM 信任库");
        for (Device device : config.devices()) {
            if (device.topic() == null
                    || device.topic().isBlank()
                    || device.topic().contains("+")
                    || device.topic().contains("#")
                    || device.protocolDeviceId() == null
                    || device.protocolDeviceId().isBlank()
                    || device.properties() == null
                    || device.properties().isEmpty()
                    || topics.putIfAbsent(device.topic(), device) != null)
                throw new IllegalArgumentException("MQTT 主题或映射无效");
            if (device.topic().startsWith("ipe/simulation/")
                    && !device.protocolDeviceId().startsWith("SIM-"))
                throw new IllegalArgumentException("模拟主题只允许 SIM- 设备");
        }
        if (topics.isEmpty()) throw new IllegalArgumentException("未配置 MQTT 设备");
        int port = b.port() == null ? (tls ? 8883 : 1883) : b.port();
        client = new MqttAsyncClient(
                (tls ? "ssl://" : "tcp://") + b.host() + ":" + port, b.clientId(), new MemoryPersistence());
        client.setCallback(new MqttCallbackExtended() {
            public void connectComplete(boolean reconnect, String serverURI) {
                for (String topic : topics.keySet())
                    try {
                        client.subscribe(topic, 1);
                    } catch (MqttException e) {
                        log.warn("MQTT 订阅失败 {}: {}", topic, e.getMessage());
                    }
            }

            public void connectionLost(Throwable cause) {
                log.warn("MQTT 连接中断: {}", cause.getMessage());
            }

            public void deliveryComplete(IMqttDeliveryToken token) {}

            public void messageArrived(String topic, MqttMessage message) {
                try {
                    if (message.getPayload().length > 65536) throw new IllegalArgumentException("消息过大");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = json.readValue(message.getPayload(), Map.class);
                    Device device = topics.get(topic);
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (var entry : device.properties().entrySet())
                        if (payload.containsKey(entry.getKey()))
                            values.put(entry.getValue(), payload.get(entry.getKey()));
                    if (values.isEmpty()) throw new IllegalArgumentException("没有匹配字段");
                    ops.properties(
                            device.protocol() == null ? "MQTT" : device.protocol(),
                            device.protocolDeviceId(),
                            device.channelNo() == null ? "" : device.channelNo(),
                            values);
                } catch (Exception e) {
                    log.error("MQTT 上报失败 topic={}: {}", topic, e.getMessage());
                }
            }
        });
        options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setConnectionTimeout(5);
        if (!username.isBlank()) {
            options.setUserName(username);
            options.setPassword(password.toCharArray());
        }
        try {
            client.connect(options).waitForCompletion(6000);
        } catch (MqttException e) {
            log.warn("MQTT 初次连接失败，将继续重试: {}", e.getMessage());
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10000)
    public void reconnect() {
        if (client != null && !client.isConnected()) {
            try {
                client.connect(options);
            } catch (MqttException e) {
                log.debug("等待 MQTT 重连: {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public void stop() {
        if (client != null)
            try {
                if (client.isConnected()) client.disconnect().waitForCompletion(2000);
                client.close();
            } catch (MqttException e) {
                log.warn("MQTT 关闭失败: {}", e.getMessage());
            }
    }

    static final class SetLocal {
        static boolean isLoopback(String host) {
            return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
        }
    }
}
