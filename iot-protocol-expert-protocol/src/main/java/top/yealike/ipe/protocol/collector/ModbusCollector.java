package top.yealike.ipe.protocol.collector;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import top.yealike.ipe.protocol.ops.OpsClient;

/** Optional read-only Modbus collector. Addresses are supplied by the vendor point map. */
@Component
public class ModbusCollector {
    private static final Logger log = LoggerFactory.getLogger(ModbusCollector.class);
    private final OpsClient ops;
    private final ObjectMapper json;
    private final String configPath;
    private ScheduledExecutorService executor;

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Config(List<Device> devices) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Device(
            String host,
            Integer port,
            Integer unitId,
            String protocolDeviceId,
            String protocol,
            Integer intervalSeconds,
            List<Point> points) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Point(
            Integer address,
            Integer function,
            String type,
            String property,
            String channelNo,
            String wordOrder,
            Double scale,
            Double offset,

            @com.fasterxml.jackson.annotation.JsonProperty("enum")
            Map<String, String> enums,

            List<Number> invalidRaw) {}

    public ModbusCollector(OpsClient ops, ObjectMapper json, @Value("${IPE_EDGE_CONFIG:}") String configPath) {
        this.ops = ops;
        this.json = json;
        this.configPath = configPath;
    }

    @PostConstruct
    public void start() throws Exception {
        if (configPath == null || configPath.isBlank()) return;
        Config config = json.readValue(Files.readString(Path.of(configPath)), Config.class);
        if (config.devices() == null || config.devices().isEmpty()) throw new IllegalArgumentException("未配置 Modbus 设备");
        for (Device device : config.devices()) validate(device);
        executor = Executors.newScheduledThreadPool(Math.min(config.devices().size(), 16));
        for (Device device : config.devices()) {
            executor.scheduleWithFixedDelay(
                    () -> {
                        try {
                            poll(device);
                        } catch (Exception ex) {
                            log.warn("Modbus 采集失败 device={}: {}", device.protocolDeviceId(), ex.getMessage());
                        }
                    },
                    0,
                    device.intervalSeconds() == null ? 10 : device.intervalSeconds(),
                    TimeUnit.SECONDS);
        }
    }

    @PreDestroy
    public void stop() {
        if (executor != null) executor.shutdownNow();
    }

    static void validate(Device d) {
        if (d.host() == null
                || d.host().isBlank()
                || d.protocolDeviceId() == null
                || d.protocolDeviceId().isBlank()
                || d.points() == null
                || d.points().isEmpty()) throw new IllegalArgumentException("Modbus 配置缺少设备或点位");
        int port = d.port() == null ? 502 : d.port(), unit = d.unitId() == null ? 1 : d.unitId();
        if (port < 1
                || port > 65535
                || unit < 0
                || unit > 247
                || (d.intervalSeconds() != null && d.intervalSeconds() < 1))
            throw new IllegalArgumentException("Modbus 端口、单元号或周期无效");
        if ((d.host().equals("127.0.0.1") || d.host().equals("localhost"))
                && port == 15020
                && !d.protocolDeviceId().startsWith("SIM-")) throw new IllegalArgumentException("模拟器只允许 SIM- 设备编号");
        Set<String> keys = new HashSet<>();
        for (Point p : d.points()) {
            int size = size(p.type());
            if (p.property() == null
                    || p.property().isBlank()
                    || p.address() == null
                    || p.address() < 0
                    || p.address() + size > 65536
                    || (p.function() != null && p.function() != 3 && p.function() != 4)
                    || (p.wordOrder() != null && !Set.of("big", "little").contains(p.wordOrder()))
                    || !keys.add((p.channelNo() == null ? "" : p.channelNo()) + "|" + p.property()))
                throw new IllegalArgumentException("Modbus 点位无效或重复");
            if ((p.scale() != null && !Double.isFinite(p.scale()))
                    || (p.offset() != null && !Double.isFinite(p.offset()))) throw new IllegalArgumentException("倍率无效");
        }
    }

    static int size(String type) {
        if (type == null) throw new IllegalArgumentException("缺少点位类型");
        return switch (type) {
            case "uint16", "int16" -> 1;
            case "uint32", "int32", "float32" -> 2;
            default -> throw new IllegalArgumentException("不支持类型: " + type);
        };
    }

    public void poll(Device device) throws Exception {
        validate(device);
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(device.host(), device.port() == null ? 502 : device.port()), 5000);
            socket.setSoTimeout(5000);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            int tx = 0;
            for (Point p : device.points()) {
                Object value =
                        readPoint(input, output, device.unitId() == null ? 1 : device.unitId(), ++tx & 0xffff, p);
                groups.computeIfAbsent(p.channelNo() == null ? "" : p.channelNo(), ignored -> new LinkedHashMap<>())
                        .put(p.property(), value);
            }
        }
        for (var group : groups.entrySet())
            ops.properties(
                    device.protocol() == null ? "MODBUS_TCP" : device.protocol(),
                    device.protocolDeviceId(),
                    group.getKey(),
                    group.getValue());
    }

    static Object readPoint(DataInputStream in, DataOutputStream out, int unit, int tx, Point p) throws Exception {
        int function = p.function() == null ? 3 : p.function(), count = size(p.type());
        out.writeShort(tx);
        out.writeShort(0);
        out.writeShort(6);
        out.writeByte(unit);
        out.writeByte(function);
        out.writeShort(p.address());
        out.writeShort(count);
        out.flush();
        int transaction = in.readUnsignedShort(),
                protocol = in.readUnsignedShort(),
                length = in.readUnsignedShort(),
                responseUnit = in.readUnsignedByte();
        if (transaction != tx || protocol != 0 || responseUnit != unit || length < 2 || length > 254)
            throw new IllegalArgumentException("Modbus 响应头无效");
        byte[] payload = in.readNBytes(length - 1);
        if (payload.length != length - 1
                || (payload[0] & 0xff) != function
                || payload.length != 2 + count * 2
                || (payload[1] & 0xff) != count * 2) throw new IllegalArgumentException("Modbus 响应体无效或异常");
        byte[] raw = java.util.Arrays.copyOfRange(payload, 2, payload.length);
        if (count == 2 && "little".equals(p.wordOrder())) raw = new byte[] {raw[2], raw[3], raw[0], raw[1]};
        ByteBuffer b = ByteBuffer.wrap(raw);
        double value = switch (p.type()) {
            case "uint16" -> Short.toUnsignedInt(b.getShort());
            case "int16" -> b.getShort();
            case "uint32" -> Integer.toUnsignedLong(b.getInt());
            case "int32" -> b.getInt();
            case "float32" -> b.getFloat();
            default -> throw new IllegalArgumentException("未知类型");
        };
        if (p.invalidRaw() != null)
            for (Number n : p.invalidRaw()) if (n.doubleValue() == value) throw new IllegalArgumentException("设备上报无效值");
        value = value * (p.scale() == null ? 1 : p.scale()) + (p.offset() == null ? 0 : p.offset());
        if (!Double.isFinite(value)) throw new IllegalArgumentException("非有限数值");
        if (p.enums() != null) {
            String key = value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
            if (!p.enums().containsKey(key)) throw new IllegalArgumentException("未知枚举值");
            return p.enums().get(key);
        }
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}
