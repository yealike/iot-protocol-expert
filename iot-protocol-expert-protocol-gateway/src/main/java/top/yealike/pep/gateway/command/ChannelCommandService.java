package top.yealike.pep.gateway.command;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import top.yealike.pep.codec.Frame;
import top.yealike.pep.codec.FrameType;
import top.yealike.pep.codec.frames.MaintenanceFrames;
import top.yealike.pep.codec.frames.OperationFrames;
import top.yealike.pep.codec.ocpp16.OcppConstants;
import top.yealike.pep.gateway.command.dto.CommandRequest;
import top.yealike.pep.gateway.command.dto.CommandResult;
import top.yealike.pep.gateway.config.properties.ProtocolProperties;
import top.yealike.pep.gateway.device.Connector;
import top.yealike.pep.gateway.device.Device;
import top.yealike.pep.gateway.device.DeviceIdentityRegistry;
import top.yealike.pep.gateway.protocol.ProtocolType;
import top.yealike.pep.gateway.protocol.ocpp16.OcppCommandFacade;
import top.yealike.pep.gateway.protocol.ocpp16.OcppSessionRegistry;
import top.yealike.pep.gateway.protocol.ykc.SessionRegistry;

/** 运维平台下发的通道命令：只做协议发送与应答解释，不建单、不记账。 */
@Service
public class ChannelCommandService {

    private final SessionRegistry ykcSessions;
    private final OcppSessionRegistry ocppSessions;
    private final CommandFacade ykcCommands;
    private final OcppCommandFacade ocppCommands;
    private final java.time.Duration commandTimeout;
    private final DeviceIdentityRegistry identities;

    public ChannelCommandService(
            SessionRegistry ykcSessions,
            OcppSessionRegistry ocppSessions,
            CommandFacade ykcCommands,
            OcppCommandFacade ocppCommands,
            ProtocolProperties properties,
            DeviceIdentityRegistry identities) {
        this.ykcSessions = ykcSessions;
        this.ocppSessions = ocppSessions;
        this.ykcCommands = ykcCommands;
        this.ocppCommands = ocppCommands;
        this.commandTimeout = java.time.Duration.ofSeconds(Math.max(properties.getPendingTimeoutSeconds(), 1));
        this.identities = identities;
    }

    public CommandResult execute(CommandRequest request) {
        try {
            return "OCPP16J".equalsIgnoreCase(request.protocol()) ? ocpp(request) : ykc(request);
        } catch (RuntimeException e) {
            String outcome =
                    e instanceof top.yealike.pep.observability.protocol.ProtocolTelemetry.TimeoutFailure timeoutFailure
                                    && timeoutFailure.isTimeout()
                            ? "TIMEOUT"
                            : "UNKNOWN";
            return new CommandResult(
                    false, e.getMessage() == null ? "命令执行失败" : e.getMessage(), Map.of("outcome", outcome));
        }
    }

    private CommandResult ykc(CommandRequest request) {
        Device identity = identities.resolve(ProtocolType.YKC16, request.protocolDeviceId());
        String deviceSn = identity.deviceSn();
        String externalPileCode = identity.thirdPartyDeviceId();
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        return switch (request.type()) {
            case "remote-start" -> {
                String transactionNo = required(params, "transactionNo");
                String gunNo = str(params, "gunNo");
                String idTag = str(params, "idTag");
                Connector connector = identity.connectorByPlatformGunNo(gunNo);
                String balanceText = str(params, "balance");
                BigDecimal balance = balanceText == null || balanceText.isBlank()
                        ? new BigDecimal("1000.00")
                        : new BigDecimal(balanceText);
                OperationFrames.RemoteStart command = new OperationFrames.RemoteStart(
                        transactionNo,
                        externalPileCode,
                        connector.externalYkcGunNo(),
                        idTag == null || idTag.isBlank() ? "0" : idTag,
                        str(params, "physicalCard"),
                        balance);
                Frame ack = ykcSessions.request(
                        deviceSn,
                        FrameType.REMOTE_START,
                        FrameType.REMOTE_START_ACK,
                        OperationFrames.encodeRemoteStart(command),
                        commandTimeout);
                OperationFrames.RemoteStartAck result = OperationFrames.decodeRemoteStartAck(ack.body());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("transactionNo", result.transactionNo());
                data.put("failReason", result.failReason());
                data.put("success", result.success());
                data.put(
                        "outcome",
                        result.success() ? "ACCEPTED" : result.failReason() == 5 ? "WAITING_STATE" : "REJECTED");
                String message = result.success()
                        ? "远程启动成功"
                        : result.failReason() == 5
                                ? "未插枪，60秒内插枪后自动补启动"
                                : "桩拒绝启动: " + startFailureReason(result.failReason());
                yield new CommandResult(result.success(), message, data);
            }
            case "remote-stop" -> {
                String gunNo = str(params, "gunNo");
                Connector connector = identity.connectorByPlatformGunNo(gunNo);
                Frame ack = ykcSessions.request(
                        deviceSn,
                        FrameType.REMOTE_STOP,
                        FrameType.STOP_ACK,
                        OperationFrames.encodeRemoteStop(
                                new OperationFrames.RemoteStop(externalPileCode, connector.externalYkcGunNo())),
                        commandTimeout);
                OperationFrames.StopAck result = OperationFrames.decodeStopAck(ack.body());
                yield new CommandResult(
                        result.success(),
                        result.success() ? "远程停机成功，等待交易记录" : "桩停机失败，原因=" + result.failReason(),
                        Map.of(
                                "gunNo",
                                gunNo,
                                "success",
                                result.success(),
                                "outcome",
                                result.success() ? "ACCEPTED" : "REJECTED"));
            }
            case "read-realtime" ->
                new CommandResult(
                        true, "读取实时数据成功", Map.of("realtime", ykcCommands.readRealtime(deviceSn, str(params, "gunNo"))));
            case "time-sync" ->
                new CommandResult(
                        true,
                        "对时成功",
                        Map.of("time", ykcCommands.timeSync(deviceSn).pileCode()));
            case "param-set" -> {
                var result = ykcCommands.paramSet(
                        deviceSn, bool(params, "allowWork", true), intVal(params, "maxPowerPercent", 100));
                yield new CommandResult(
                        result.success(), result.success() ? "参数设置成功" : "参数设置失败", Map.of("success", result.success()));
            }
            case "reboot" -> {
                var result = ykcCommands.reboot(deviceSn, intVal(params, "control", 1));
                yield new CommandResult(
                        result.success(), result.success() ? "重启命令已接受" : "重启失败", Map.of("success", result.success()));
            }
            case "update" -> {
                var update = new MaintenanceFrames.Update(
                        deviceSn,
                        intVal(params, "pileModel", 1),
                        intVal(params, "power", 0),
                        str(params, "serverAddress"),
                        intVal(params, "port", 0),
                        str(params, "username"),
                        str(params, "password"),
                        str(params, "filePath"),
                        intVal(params, "control", 1),
                        intVal(params, "downloadTimeoutMinutes", 10));
                var result = ykcCommands.update(deviceSn, update);
                yield new CommandResult(
                        result.status() == 0, "升级命令状态=" + result.status(), Map.of("status", result.status()));
            }
            case "rate-model" -> {
                var result = ykcCommands.pushRateModel(deviceSn);
                yield new CommandResult(
                        result.success(),
                        result.success() ? "计费模型下发成功" : "计费模型下发失败",
                        Map.of("success", result.success()));
            }
            case "balance" -> {
                var result = ykcCommands.balance(
                        deviceSn,
                        str(params, "gunNo"),
                        str(params, "physicalCard"),
                        decimal(params, "balance", new BigDecimal("1000.00")));
                yield new CommandResult(
                        result.result() == 1, "余额更新结果=" + result.result(), Map.of("result", result.result()));
            }
            case "cards-sync" -> {
                var cards = toCards(params);
                var result = ykcCommands.cardSync(deviceSn, cards);
                yield new CommandResult(
                        result != null && result.success(),
                        "离线卡同步完成",
                        Map.of("success", result != null && result.success()));
            }
            case "cards-clear" -> {
                var result = ykcCommands.cardClear(deviceSn, toStringList(params.get("cards")));
                yield new CommandResult(
                        true, "离线卡清除完成", Map.of("count", result.results().size()));
            }
            case "cards-query" -> {
                var result = ykcCommands.cardQuery(deviceSn, toStringList(params.get("cards")));
                yield new CommandResult(
                        true, "离线卡查询完成", Map.of("count", result.results().size()));
            }
            case "lock" -> {
                var result = ykcCommands.lockControl(
                        deviceSn, String.format("%02d", intVal(params, "gunNo", 1)), intVal(params, "action", 1));
                yield new CommandResult(
                        result.success(), result.success() ? "地锁控制成功" : "地锁控制失败", Map.of("success", result.success()));
            }
            default -> new CommandResult(false, "云快充不支持命令: " + request.type(), Map.of());
        };
    }

    private CommandResult ocpp(CommandRequest request) {
        Device identity = identities.resolve(ProtocolType.OCPP16J, request.protocolDeviceId());
        String code = identity.deviceSn();
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        JsonNode reply;
        switch (request.type()) {
            case "remote-start" -> {
                int connectorId = externalOcppConnector(identity, params, "gunNo", 1);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("connectorId", connectorId);
                payload.put("idTag", str(params, "idTag") == null ? "IPE-APP" : str(params, "idTag"));
                reply = ocppCommands.rawCall(code, OcppConstants.REMOTE_START_TRANSACTION, payload);
                String status =
                        reply == null ? "Rejected" : reply.path("status").asString("Rejected");
                boolean accepted = OcppConstants.ACCEPTED.equals(status);
                return new CommandResult(
                        accepted,
                        accepted ? "OCPP 远程启动已受理，等待设备 StartTransaction" : "设备拒绝远程启动: " + status,
                        Map.of("status", status, "outcome", accepted ? "ACCEPTED" : "REJECTED"));
            }
            case "remote-stop" -> {
                Integer transactionId = intVal(params, "transactionId", 0);
                reply = ocppCommands.rawCall(
                        code, OcppConstants.REMOTE_STOP_TRANSACTION, Map.of("transactionId", transactionId));
                String status =
                        reply == null ? "Rejected" : reply.path("status").asString("Rejected");
                boolean accepted = OcppConstants.ACCEPTED.equals(status);
                return new CommandResult(
                        accepted,
                        accepted ? "OCPP 远程停机已受理，等待 StopTransaction" : "设备拒绝远程停机: " + status,
                        Map.of("status", status, "outcome", accepted ? "ACCEPTED" : "REJECTED"));
            }
            case "reset" -> {
                return result(ocppCommands.reset(code, str(params, "mode")), "重启命令已发送");
            }
            case "unlock" -> {
                return result(
                        ocppCommands.unlockConnector(code, externalOcppConnector(identity, params, "connectorId", 1)),
                        "解锁命令已发送");
            }
            case "configuration" -> {
                if ("GET".equalsIgnoreCase(str(params, "method"))) {
                    return new CommandResult(
                            true,
                            "读取配置成功",
                            Map.of(
                                    "configuration",
                                    ocppCommands.getConfiguration(code, toStringList(params.get("keys")))));
                }
                return result(
                        ocppCommands.changeConfiguration(code, str(params, "key"), str(params, "value")), "配置修改已发送");
            }
            case "availability" -> {
                return result(
                        ocppCommands.changeAvailability(
                                code, externalOcppConnector(identity, params, "connectorId", 0), str(params, "type")),
                        "可用性切换已发送");
            }
            case "trigger" -> {
                return result(
                        ocppCommands.triggerMessage(
                                code,
                                str(params, "requestedMessage"),
                                externalOcppConnector(identity, params, "connectorId", 0)),
                        "触发消息已发送");
            }
            case "clear-cache" -> {
                return result(ocppCommands.clearCache(code), "清缓存已发送");
            }
            case "data-transfer" -> {
                return result(
                        ocppCommands.dataTransfer(
                                code, str(params, "vendorId"), str(params, "messageId"), str(params, "data")),
                        "透传消息已发送");
            }
            case "diagnostics" -> {
                return result(
                        ocppCommands.getDiagnostics(
                                code,
                                str(params, "location"),
                                intVal(params, "retries", 0),
                                intVal(params, "retryInterval", 0)),
                        "诊断请求已发送");
            }
            case "firmware" -> {
                return result(
                        ocppCommands.updateFirmware(code, str(params, "location"), str(params, "retrieveDate")),
                        "固件升级请求已发送");
            }
            case "raw-call" -> {
                Object payload = params.get("payload");
                @SuppressWarnings("unchecked")
                Map<String, Object> map = payload instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                return result(ocppCommands.rawCall(code, str(params, "action"), map), "原始调用已发送");
            }
            default -> {
                return new CommandResult(false, "OCPP 不支持命令: " + request.type(), Map.of());
            }
        }
    }

    private static CommandResult result(JsonNode reply, String successMessage) {
        if (reply == null) {
            return new CommandResult(false, "设备未返回应答", Map.of());
        }
        if (reply.has("errorCode")) {
            String code = reply.path("errorCode").asString("Error");
            String description = reply.path("errorDescription").asString("");
            return new CommandResult(
                    false,
                    "设备返回错误: " + code + (description.isBlank() ? "" : " " + description),
                    Map.of("reply", reply));
        }
        String status = reply.path("status").asString("");
        if (List.of(
                        OcppConstants.REJECTED,
                        OcppConstants.NOT_SUPPORTED,
                        "NotImplemented",
                        "UnlockFailed",
                        "UnknownMessageId",
                        "UnknownVendorId")
                .contains(status)) {
            return new CommandResult(false, "设备拒绝命令: " + status, Map.of("reply", reply));
        }
        return new CommandResult(true, successMessage, Map.of("reply", reply));
    }

    private static String str(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String required(Map<String, Object> params, String key) {
        String value = str(params, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少命令参数: " + key);
        }
        return value;
    }

    private static int intVal(Map<String, Object> params, String key, int fallback) {
        Object value = params.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int externalOcppConnector(
            Device identity, Map<String, Object> params, String preferredKey, int fallback) {
        Object raw = params.get(preferredKey);
        if (raw == null && !"gunNo".equals(preferredKey)) {
            raw = params.get("gunNo");
        }
        if (raw == null) {
            raw = fallback;
        }
        String value = String.valueOf(raw).trim();
        if (value.matches("[0-9]{10}")) {
            value = value.substring(8);
        }
        if (value.matches("[0-9]{1,2}")) {
            int number = Integer.parseInt(value);
            if (number == 0) {
                return 0;
            }
            return identity.connectorByPlatformGunNo(String.format("%02d", number))
                    .externalOcppConnectorId();
        }
        throw new IllegalArgumentException("OCPP 平台枪号格式非法: " + raw);
    }

    private static boolean bool(Map<String, Object> params, String key, boolean fallback) {
        Object value = params.get(key);
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static BigDecimal decimal(Map<String, Object> params, String key, BigDecimal fallback) {
        Object value = params.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<String> toStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static List<OperationFrames.OfflineCard> toCards(Map<String, Object> params) {
        Object value = params.get("cards");
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item instanceof Map<?, ?>)
                .map(item -> {
                    Map<?, ?> card = (Map<?, ?>) item;
                    Object logical = card.get("logicalCard");
                    Object physical = card.get("physicalCard");
                    return new OperationFrames.OfflineCard(
                            logical == null ? "" : String.valueOf(logical),
                            physical == null ? "" : String.valueOf(physical));
                })
                .toList();
    }

    private static String startFailureReason(int code) {
        return switch (code) {
            case 0x01 -> "设备编号不匹配";
            case 0x02 -> "枪已在充电";
            case 0x03 -> "设备故障";
            case 0x04 -> "设备离线";
            case 0x05 -> "未插枪";
            default -> "未知原因(" + code + ")";
        };
    }
}
