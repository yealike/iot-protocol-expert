package top.yealike.ipe.simulator.multidevice;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Seven simulated devices, with local Modbus and optional MQTT telemetry. */
@Component
public class MultiDeviceRuntime {
    private static final Logger log = LoggerFactory.getLogger(MultiDeviceRuntime.class);
    private final boolean enabled;
    private final int port;
    private final String mqttUrl;
    private final ObjectMapper json;
    private ServerSocket server;
    private ExecutorService executor;
    private MqttAsyncClient mqtt;
    private MqttConnectOptions mqttOptions;
    private boolean running = true, fault, pv = true, wind = true, lockLowered, gateOpen, recording = true;
    private String storage = "discharging";
    private double soc = 65;
    private long started = System.nanoTime(), last = started;
    private final Map<String, Map<String, Object>> completed = new LinkedHashMap<>();

    public MultiDeviceRuntime(
            @Value("${IPE_SIM_MULTI_ENABLED:false}") boolean enabled,
            @Value("${IPE_SIM_MODBUS_PORT:15020}") int port,
            @Value("${IPE_SIM_MQTT_URL:}") String mqttUrl,
            ObjectMapper json) {
        this.enabled = enabled;
        this.port = port;
        this.mqttUrl = mqttUrl;
        this.json = json;
    }

    @PostConstruct
    public void start() throws Exception {
        if (!enabled) return;
        server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        executor = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "multi-simulator");
            t.setDaemon(true);
            return t;
        });
        executor.submit(() -> {
            while (!server.isClosed())
                try {
                    Socket socket = server.accept();
                    executor.submit(() -> serve(socket));
                } catch (Exception e) {
                    if (!server.isClosed()) log.warn("模拟 Modbus 连接失败: {}", e.getMessage());
                }
        });
        if (mqttUrl != null && !mqttUrl.isBlank()) {
            if (!mqttUrl.startsWith("ssl://")
                    && !(mqttUrl.startsWith("tcp://127.0.0.1:") || mqttUrl.startsWith("tcp://localhost:")))
                throw new IllegalArgumentException("明文 MQTT 仅允许本机");
            mqtt = new MqttAsyncClient(mqttUrl, "ipe-simulator-demo", new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setConnectionTimeout(5);
            String username = System.getenv("IPE_MQTT_USERNAME"), password = System.getenv("IPE_MQTT_PASSWORD");
            if (username != null && !username.isBlank()) {
                options.setUserName(username);
                options.setPassword((password == null ? "" : password).toCharArray());
            }
            mqttOptions = options;
            try {
                mqtt.connect(options).waitForCompletion(6000);
            } catch (MqttException e) {
                log.warn("模拟 MQTT 未连接: {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public void stop() throws Exception {
        if (server != null) server.close();
        if (executor != null) executor.shutdownNow();
        if (mqtt != null) {
            if (mqtt.isConnected()) mqtt.disconnect().waitForCompletion(2000);
            mqtt.close();
        }
    }

    synchronized int[] registers(int unit) {
        long now = System.nanoTime();
        double elapsed = (now - started) / 1e9, dt = Math.min(5, (now - last) / 1e9);
        last = now;
        double wave = Math.sin(elapsed / 30), power = 12 + wave * 6;
        if (running && !storage.equals("idle")) {
            double change = power * dt * 20 / (215 * 3600) * 100;
            soc = Math.min(100, Math.max(5, soc + (storage.equals("charging") ? change : -change)));
            if (soc <= 5 || soc >= 100) storage = "idle";
        }
        return switch (unit) {
            case 1 ->
                new int[] {(int) (650 + wave * 180), pv ? (int) (680 + wave * 160) : 0, fault ? 760 : 320, pv ? 1 : 0};
            case 2 ->
                new int[] {(int) (75 + wave * 15), wind ? (int) (620 + wave * 180) : 0, fault ? 900 : 230, wind ? 1 : 0
                };
            case 3 ->
                new int[] {
                    (int) Math.round(soc * 10),
                    (storage.equals("idle") ? 0 : (int) (power * 10) * (storage.equals("charging") ? -1 : 1)) & 0xffff,
                    fault ? 610 : 320,
                    35,
                    storage.equals("charging") ? 2 : storage.equals("discharging") ? 1 : 0
                };
            case 4 -> new int[] {(int) (300 + wave * 70), 5000, 1};
            default -> null;
        };
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(10000);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());
            while (!socket.isClosed()) {
                int tx = in.readUnsignedShort(),
                        protocol = in.readUnsignedShort(),
                        length = in.readUnsignedShort(),
                        unit = in.readUnsignedByte();
                if (protocol != 0 || length != 6) return;
                int function = in.readUnsignedByte(), address = in.readUnsignedShort(), count = in.readUnsignedShort();
                synchronized (this) {
                    if (!running) return;
                }
                int[] values = registers(unit);
                boolean ok = values != null
                        && (function == 3 || function == 4)
                        && count > 0
                        && count <= 125
                        && address + count <= values.length;
                out.writeShort(tx);
                out.writeShort(0);
                out.writeShort(ok ? 3 + count * 2 : 3);
                out.writeByte(unit);
                if (ok) {
                    out.writeByte(function);
                    out.writeByte(count * 2);
                    for (int i = 0; i < count; i++) out.writeShort(values[address + i]);
                } else {
                    out.writeByte(function | 0x80);
                    out.writeByte(2);
                }
                out.flush();
            }
        } catch (Exception ignored) {
            /* disconnected or timed out */
        }
    }

    public synchronized Map<String, Object> command(String requestId, String device, String action) {
        if (!enabled || !running) throw new IllegalStateException("多设备模拟未运行");
        if (requestId == null || requestId.isBlank() || requestId.length() > 80 || device == null || action == null)
            throw new IllegalArgumentException("命令格式无效");
        var previous = completed.get(requestId);
        if (previous != null) {
            if (!device.equals(previous.get("device")) || !action.equals(previous.get("action")))
                throw new IllegalStateException("请求编号冲突");
            return previous;
        }
        String state;
        switch (device) {
            case "SIM-PV-001" -> {
                if (!action.equals("start") && !action.equals("stop")) throw new IllegalArgumentException("未知命令");
                pv = action.equals("start");
                state = pv ? "running" : "standby";
            }
            case "SIM-WIND-001" -> {
                if (!action.equals("start") && !action.equals("stop")) throw new IllegalArgumentException("未知命令");
                wind = action.equals("start");
                state = wind ? "running" : "idle";
            }
            case "SIM-ESS-001" -> {
                storage = switch (action) {
                    case "charge" -> "charging";
                    case "discharge" -> "discharging";
                    case "standby" -> "idle";
                    default -> throw new IllegalArgumentException("未知命令");
                };
                state = storage;
            }
            case "SIM-METER-001" -> {
                if (!action.equals("sample")) throw new IllegalArgumentException("未知命令");
                state = "online";
            }
            case "SIM-LOCK-001" -> {
                if (!action.equals("lower") && !action.equals("raise")) throw new IllegalArgumentException("未知命令");
                lockLowered = action.equals("lower");
                state = lockLowered ? "lowered" : "raised";
            }
            case "SIM-GATE-001" -> {
                if (!action.equals("open") && !action.equals("close")) throw new IllegalArgumentException("未知命令");
                gateOpen = action.equals("open");
                state = gateOpen ? "open" : "closed";
            }
            case "SIM-CAM-001" -> {
                if (!action.equals("startRecord") && !action.equals("stopRecord"))
                    throw new IllegalArgumentException("未知命令");
                recording = action.equals("startRecord");
                state = "online";
            }
            default -> throw new IllegalArgumentException("未知模拟设备");
        }
        Map<String, Object> result =
                Map.of("requestId", requestId, "device", device, "action", action, "state", state, "success", true);
        completed.put(requestId, result);
        if (completed.size() > 1000)
            completed.remove(completed.keySet().iterator().next());
        return result;
    }

    public synchronized void scenario(String action) {
        if (!enabled) throw new IllegalStateException("多设备模拟未启用");
        switch (action) {
            case "fault" -> fault = !fault;
            case "pause" -> running = !running;
            default -> throw new IllegalArgumentException("未知场景");
        }
    }

    public synchronized Map<String, Object> snapshot() {
        int[] a = registers(1), b = registers(2), c = registers(3), d = registers(4);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("running", running);
        result.put("fault", fault);
        result.put("mqttConnected", mqtt != null && mqtt.isConnected());
        result.put("pv", Map.of("irradiance", a[0], "power", a[1] / 10d, "temperature", a[2] / 10d));
        result.put("wind", Map.of("windSpeed", b[0] / 10d, "power", b[1], "vibration", b[2] / 100d));
        result.put(
                "storage",
                Map.of("soc", c[0] / 10d, "power", (short) c[1] / 10d, "temperature", c[2] / 10d, "state", storage));
        result.put("meter", Map.of("power", d[0] / 10d, "frequency", d[1] / 100d));
        result.put("lock", Map.of("state", lockLowered ? "lowered" : "raised"));
        result.put("gate", Map.of("state", gateOpen ? "open" : "closed"));
        result.put("camera", Map.of("recordEnabled", recording));
        return result;
    }

    @Scheduled(fixedDelay = 5000)
    public void publish() {
        Map<String, Map<String, Object>> values;
        synchronized (this) {
            if (!enabled || !running || mqtt == null) return;
            if (!mqtt.isConnected()) {
                try {
                    mqtt.connect(mqttOptions);
                } catch (MqttException e) {
                    log.debug("等待模拟 MQTT 重连: {}", e.getMessage());
                }
                return;
            }
            values = Map.of(
                    "SIM-LOCK-001",
                    Map.of("state", lockLowered ? "lowered" : "raised", "batteryPercent", 85, "occupied", lockLowered),
                    "SIM-GATE-001",
                    Map.of("state", gateOpen ? "open" : "closed", "passCountToday", 12),
                    "SIM-CAM-001",
                    Map.of("state", "online", "storageUsedPercent", 42.5, "recordEnabled", recording));
        }
        for (var entry : values.entrySet())
            try {
                mqtt.publish(
                        "ipe/simulation/" + entry.getKey() + "/properties",
                        new MqttMessage(json.writeValueAsBytes(entry.getValue())));
            } catch (Exception e) {
                log.warn("模拟 MQTT 上报失败: {}", e.getMessage());
            }
    }
}
