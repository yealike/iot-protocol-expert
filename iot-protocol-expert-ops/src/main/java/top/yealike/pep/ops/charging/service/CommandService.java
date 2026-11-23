package top.yealike.pep.ops.charging.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import top.yealike.pep.ops.api.internal.dto.CommandRequest;
import top.yealike.pep.ops.api.internal.dto.CommandResult;
import top.yealike.pep.ops.audit.service.AuditService;
import top.yealike.pep.ops.charging.domain.ChargeOrder;
import top.yealike.pep.ops.charging.domain.CommandRecord;
import top.yealike.pep.ops.charging.mapper.CommandRecordMapper;
import top.yealike.pep.ops.config.properties.OpsProperties;
import top.yealike.pep.ops.devices.domain.DeviceInstance;
import top.yealike.pep.ops.devices.mapper.DeviceProtocolMappingMapper;
import top.yealike.pep.ops.devices.service.DeviceService;
import top.yealike.pep.ops.devices.service.ProtocolClient;

@Service
public class CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);

    private final CommandRecordMapper commands;
    private final DeviceService devices;
    private final DeviceProtocolMappingMapper mappings;
    private final OrderService orders;
    private final ProtocolClient protocol;
    private final OpsProperties properties;
    private final AuditService audits;
    private final ObjectMapper json;

    public CommandService(
            CommandRecordMapper commands,
            DeviceService devices,
            DeviceProtocolMappingMapper mappings,
            OrderService orders,
            ProtocolClient protocol,
            OpsProperties properties,
            AuditService audits,
            ObjectMapper json) {
        this.commands = commands;
        this.devices = devices;
        this.mappings = mappings;
        this.orders = orders;
        this.protocol = protocol;
        this.properties = properties;
        this.audits = audits;
        this.json = json;
    }

    /** 先保存命令记录，再投递协议通道，最后回写发送与应答结果。 */
    public CommandRecord submit(Long deviceId, String type, Map<String, Object> params, String operator) {
        DeviceInstance device = devices.get(deviceId);
        if (!device.enabled) {
            throw new IllegalArgumentException("设备已停用: " + device.deviceCode);
        }
        String protocolDeviceId = protocolDeviceId(deviceId);
        CommandRecord record = new CommandRecord();
        record.deviceId = device.id;
        record.protocol = device.protocol;
        record.commandType = type;
        record.requestPayload = toJson(params);
        record.status = CommandRecord.Status.PENDING;
        record.operator = operator;
        record.createdAt = LocalDateTime.now();
        record.updatedAt = LocalDateTime.now();
        record = commands.save(record);

        try {
            CommandResult result = protocol.execute(
                    new CommandRequest(
                            device.protocol, protocolDeviceId, type, params == null ? Map.of() : params, operator),
                    properties.getServiceToken());
            record.sentAt = LocalDateTime.now();
            record.ackAt = LocalDateTime.now();
            record.responsePayload = toJson(result);
            record.status = classify(result);
        } catch (RuntimeException e) {
            record.ackAt = LocalDateTime.now();
            record.status = CommandRecord.Status.UNKNOWN;
            record.responsePayload = toJson(Map.of("error", e.getMessage() == null ? "协议通道不可用" : e.getMessage()));
            log.warn("设备 {} 命令 {} 投递失败: {}", device.deviceCode, type, e.getMessage());
        }
        record.updatedAt = LocalDateTime.now();
        record = commands.save(record);
        audits.record(
                operator,
                "command." + type,
                "device",
                String.valueOf(device.id),
                record.status.name(),
                record.responsePayload);
        return record;
    }

    public Map<String, Object> remoteStart(
            Long deviceId, String gunNo, String idTag, String physicalCard, String balanceText, String operator) {
        DeviceInstance device = devices.get(deviceId);
        if (!device.allowWork) {
            throw new IllegalArgumentException("设备已停用，拒绝启动: " + device.deviceCode);
        }
        if (!device.isOnline()) {
            throw new IllegalArgumentException("设备不在线: " + device.deviceCode);
        }
        validateGun(device, gunNo);
        boolean ocpp = "OCPP16J".equals(device.protocol);
        String normalizedIdTag = idTag == null || idTag.isBlank() ? (ocpp ? "IPE-APP" : "0") : idTag;
        ChargeOrder order = orders.create(device.id, gunNo, 1, physicalCard, normalizedIdTag, device.protocol, ocpp);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("orderNo", order.orderNo);
        params.put("transactionNo", orders.transactionNo(order));
        params.put("gunNo", gunNo);
        params.put("idTag", normalizedIdTag);
        params.put("physicalCard", physicalCard);
        params.put("balance", balanceText == null || balanceText.isBlank() ? "1000.00" : balanceText);
        CommandRecord record = submit(device.id, "remote-start", params, operator);
        if (record.status == CommandRecord.Status.WAITING_STATE) {
            // 未插枪：订单保持待启动，60 秒内插枪后设备会补发启动确认。
        } else if (record.status == CommandRecord.Status.REJECTED) {
            orders.fail(order.orderNo, "远程启动失败: " + record.responsePayload);
        } else {
            // Accepted 只表示命令已受理；超时或通道异常同样不代表设备没有执行。
            // 订单保留为 PENDING，等待设备 StartTransaction 或 90 秒超时回收。
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", order.orderNo);
        result.put("commandId", record.id);
        result.put("status", record.status.name());
        result.put(
                "success",
                record.status == CommandRecord.Status.SUCCESS || record.status == CommandRecord.Status.WAITING_STATE);
        result.put("message", record.responsePayload);
        return result;
    }

    public Map<String, Object> remoteStop(Long deviceId, String gunNo, String operator) {
        DeviceInstance device = devices.get(deviceId);
        validateGun(device, gunNo);
        ChargeOrder order = orders.active(device.id, gunNo);
        if (order == null) {
            throw new IllegalArgumentException("枪 " + gunNo + " 没有进行中的订单");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("orderNo", order.orderNo);
        params.put("gunNo", gunNo);
        String transactionNo = orders.transactionNo(order);
        if (transactionNo != null) {
            params.put("transactionNo", transactionNo);
            if ("OCPP16J".equals(device.protocol)) {
                params.put("transactionId", Integer.parseInt(transactionNo));
            }
        }
        CommandRecord record = submit(device.id, "remote-stop", params, operator);
        if (record.status == CommandRecord.Status.SUCCESS && !"OCPP16J".equals(device.protocol)) {
            // 云快充的停机会影响已有交易流程；OCPP 的 Accepted 只代表已受理，
            // 必须由随后的 StopTransaction 驱动结算。
            orders.stop(order.orderNo, 0x40);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", order.orderNo);
        result.put("commandId", record.id);
        result.put("status", record.status.name());
        result.put(
                "success",
                record.status == CommandRecord.Status.SUCCESS || record.status == CommandRecord.Status.WAITING_STATE);
        result.put("message", record.responsePayload);
        return result;
    }

    public List<CommandRecord> list(Long deviceId) {
        return deviceId == null
                ? commands.findTop100ByOrderByIdDesc()
                : commands.findTop100ByDeviceIdOrderByIdDesc(deviceId);
    }

    private void validateGun(DeviceInstance device, String gunNo) {
        if (gunNo == null || !gunNo.matches("[0-9]{2}")) {
            throw new IllegalArgumentException("枪号格式必须为两位数字");
        }
        int gun = Integer.parseInt(gunNo);
        if (gun < 1 || gun > devices.gunCount(device.id)) {
            throw new IllegalArgumentException("枪号不属于设备");
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (RuntimeException e) {
            return String.valueOf(value);
        }
    }

    private String protocolDeviceId(Long deviceId) {
        return mappings.findByDeviceId(deviceId)
                .filter(mapping -> mapping.enabled)
                .map(mapping -> mapping.thirdPartyDeviceId)
                .orElseThrow(() -> new IllegalArgumentException("设备未配置第三方协议设备号: " + deviceId));
    }

    private static CommandRecord.Status classify(CommandResult result) {
        Object structured = result.data() == null ? null : result.data().get("outcome");
        if (structured != null) {
            String outcome = String.valueOf(structured);
            if ("REJECTED".equals(outcome)) return CommandRecord.Status.REJECTED;
            if ("TIMEOUT".equals(outcome)) return CommandRecord.Status.TIMEOUT;
            if ("WAITING_STATE".equals(outcome)) return CommandRecord.Status.WAITING_STATE;
            if ("ACCEPTED".equals(outcome)) return CommandRecord.Status.SUCCESS;
            if ("UNKNOWN".equals(outcome)) return CommandRecord.Status.UNKNOWN;
        }
        if (result.success()) return CommandRecord.Status.SUCCESS;
        String message = result.message() == null ? "" : result.message();
        if (message.contains("超时")) return CommandRecord.Status.TIMEOUT;
        if (message.contains("未插枪")) return CommandRecord.Status.WAITING_STATE;
        if (message.contains("拒绝") || message.contains("不支持")) {
            return CommandRecord.Status.REJECTED;
        }
        return CommandRecord.Status.UNKNOWN;
    }
}
