package top.yealike.ipe.ops.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import top.yealike.ipe.ops.device.entity.DeviceInstance;
import top.yealike.ipe.ops.domain.CommandRecord;
import top.yealike.ipe.ops.repository.CommandRecordRepository;

/** Local laboratory commands only. Never routes a simulated command to physical devices. */
@Service
public class SimulationCommandService {
    private static final Map<String, Set<String>> ACTIONS = Map.of(
            "PV", Set.of("start", "stop"),
            "WIND", Set.of("start", "stop"),
            "STORAGE", Set.of("charge", "discharge", "standby"),
            "METER", Set.of("sample"),
            "LOCK", Set.of("lower", "raise"),
            "GATE", Set.of("open", "close"),
            "CAMERA", Set.of("startRecord", "stopRecord"));
    private static final Map<String, String> DEVICES = Map.of(
            "SIM-PV-001",
            "PV",
            "SIM-WIND-001",
            "WIND",
            "SIM-ESS-001",
            "STORAGE",
            "SIM-METER-001",
            "METER",
            "SIM-LOCK-001",
            "LOCK",
            "SIM-GATE-001",
            "GATE",
            "SIM-CAM-001",
            "CAMERA");
    private static final Map<String, String> PROTOCOLS = Map.of(
            "SIM-PV-001", "MODBUS_TCP",
            "SIM-WIND-001", "MODBUS_TCP",
            "SIM-ESS-001", "MODBUS_TCP",
            "SIM-METER-001", "MODBUS_TCP",
            "SIM-LOCK-001", "MQTT",
            "SIM-GATE-001", "MQTT",
            "SIM-CAM-001", "ONVIF");
    private final DeviceService devices;
    private final CommandRecordRepository records;
    private final AuditService audits;
    private final ObjectMapper json;
    private final String token;
    private final URI commandUrl;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public SimulationCommandService(
            DeviceService devices,
            CommandRecordRepository records,
            AuditService audits,
            ObjectMapper json,
            @Value("${IPE_SIM_COMMAND_TOKEN:}") String token,
            @Value("${IPE_SIM_COMMAND_URL:http://127.0.0.1:19081/command}") String commandUrl) {
        this.devices = devices;
        this.records = records;
        this.audits = audits;
        this.json = json;
        this.token = token;
        this.commandUrl = URI.create(commandUrl);
        if (!"http".equals(this.commandUrl.getScheme())
                || !"127.0.0.1".equals(this.commandUrl.getHost())
                || this.commandUrl.getUserInfo() != null
                || this.commandUrl.getPort() < 1
                || !"/command".equals(this.commandUrl.getPath())
                || this.commandUrl.getQuery() != null
                || this.commandUrl.getFragment() != null) {
            throw new IllegalArgumentException("模拟指令地址必须为本机 HTTP /command");
        }
    }

    public CommandRecord submit(Long deviceId, String action, String operator) {
        DeviceInstance device = devices.get(deviceId);
        if (!device.enabled
                || !device.allowSimulate
                || !device.allowWork
                || action == null
                || device.protocolDeviceId == null
                || device.deviceType == null
                || !device.deviceType.equals(DEVICES.get(device.protocolDeviceId))
                || !PROTOCOLS.get(device.protocolDeviceId).equals(device.protocol)
                || !ACTIONS.getOrDefault(device.deviceType, Set.of()).contains(action)) {
            throw new IllegalArgumentException("设备或命令不允许模拟控制");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("未配置模拟指令令牌");
        }
        String requestId = UUID.randomUUID().toString();
        var payload = Map.of("requestId", requestId, "device", device.protocolDeviceId, "action", action);
        CommandRecord record = new CommandRecord();
        record.deviceId = device.id;
        record.protocol = device.protocol;
        record.commandType = "sim:" + action;
        record.operator = operator;
        record.status = CommandRecord.Status.PENDING;
        record.requestPayload = json.writeValueAsString(payload);
        record.createdAt = record.updatedAt = LocalDateTime.now();
        record = records.save(record);
        try {
            HttpRequest request = HttpRequest.newBuilder(commandUrl)
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .header("X-Simulation-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(record.requestPayload))
                    .build();
            record.sentAt = LocalDateTime.now();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            record.ackAt = LocalDateTime.now();
            if (response.statusCode() == 200) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = json.readValue(response.body(), Map.class);
                if (!requestId.equals(body.get("requestId"))
                        || !device.protocolDeviceId.equals(body.get("device"))
                        || !action.equals(body.get("action"))
                        || !Boolean.TRUE.equals(body.get("success"))) {
                    record.status = CommandRecord.Status.UNKNOWN;
                    record.responsePayload = "模拟器响应无效";
                } else {
                    record.status = CommandRecord.Status.SUCCESS;
                    record.responsePayload = json.writeValueAsString(body);
                }
            } else {
                record.status =
                        response.statusCode() >= 400 && response.statusCode() < 500 && response.statusCode() != 408
                                ? CommandRecord.Status.REJECTED
                                : CommandRecord.Status.UNKNOWN;
                record.responsePayload = "模拟器响应：HTTP " + response.statusCode();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            record.status = CommandRecord.Status.UNKNOWN;
            record.responsePayload = "请求中断，执行结果未知";
        } catch (Exception e) {
            record.status = CommandRecord.Status.UNKNOWN;
            record.responsePayload = "模拟器不可达或响应无效，执行结果未知";
        }
        record.updatedAt = LocalDateTime.now();
        record = records.save(record);
        audits.record(
                operator,
                "simulation." + action,
                "device",
                String.valueOf(device.id),
                record.status.name(),
                record.responsePayload);
        return record;
    }
}
