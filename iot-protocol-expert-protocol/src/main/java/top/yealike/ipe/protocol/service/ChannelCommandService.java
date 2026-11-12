package top.yealike.ipe.protocol.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import top.yealike.ipe.protocol.codec.Frame;
import top.yealike.ipe.protocol.codec.FrameType;
import top.yealike.ipe.protocol.codec.frames.MaintenanceFrames;
import top.yealike.ipe.protocol.codec.frames.OperationFrames;
import top.yealike.ipe.protocol.netty.SessionRegistry;
import top.yealike.ipe.protocol.ocpp16.OcppCommandFacade;
import top.yealike.ipe.protocol.ocpp16.OcppSessionRegistry;
import top.yealike.ipe.protocol.ocpp16.codec.OcppConstants;
import top.yealike.ipe.protocol.ops.OpsDtos;

/** 运维平台下发的通道命令：只做协议发送与应答解释，不建单、不记账。 */
@Service
public class ChannelCommandService {

    private final SessionRegistry ykcSessions;
    private final OcppSessionRegistry ocppSessions;
    private final CommandFacade ykcCommands;
    private final OcppCommandFacade ocppCommands;

    public ChannelCommandService(
            SessionRegistry ykcSessions,
            OcppSessionRegistry ocppSessions,
            CommandFacade ykcCommands,
            OcppCommandFacade ocppCommands) {
        this.ykcSessions = ykcSessions;
        this.ocppSessions = ocppSessions;
        this.ykcCommands = ykcCommands;
        this.ocppCommands = ocppCommands;
    }

    public OpsDtos.CommandResult execute(OpsDtos.CommandRequest request) {
        try {
            return "OCPP16J".equalsIgnoreCase(request.protocol()) ? ocpp(request) : ykc(request);
        } catch (RuntimeException e) {
            return new OpsDtos.CommandResult(false, e.getMessage() == null ? "命令执行失败" : e.getMessage(), Map.of());
        }
    }

    private OpsDtos.CommandResult ykc(OpsDtos.CommandRequest request) {
        String pileCode = request.protocolDeviceId();
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        return switch (request.type()) {
            case "remote-start" -> {
                String orderNo = str(params, "orderNo");
                String gunNo = str(params, "gunNo");
                String idTag = str(params, "idTag");
                String balanceText = str(params, "balance");
                BigDecimal balance = balanceText == null || balanceText.isBlank()
                        ? new BigDecimal("1000.00")
                        : new BigDecimal(balanceText);
                OperationFrames.RemoteStart command = new OperationFrames.RemoteStart(
                        orderNo, pileCode, gunNo, idTag == null ? "IPE-APP" : idTag, idTag, balance);
                Frame ack = ykcSessions.request(
                        pileCode,
                        FrameType.REMOTE_START,
                        FrameType.REMOTE_START_ACK,
                        OperationFrames.encodeRemoteStart(command),
                        java.time.Duration.ofSeconds(15));
                OperationFrames.RemoteStartAck result = OperationFrames.decodeRemoteStartAck(ack.body());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("transactionNo", result.transactionNo());
                data.put("failReason", result.failReason());
                data.put("success", result.success());
                String message = result.success()
                        ? "远程启动成功"
                        : result.failReason() == 5
                                ? "未插枪，60秒内插枪后自动补启动"
                                : "桩拒绝启动: " + startFailureReason(result.failReason());
                yield new OpsDtos.CommandResult(result.success(), message, data);
            }
            case "remote-stop" -> {
                String gunNo = str(params, "gunNo");
                Frame ack = ykcSessions.request(
                        pileCode,
                        FrameType.REMOTE_STOP,
                        FrameType.STOP_ACK,
                        OperationFrames.encodeRemoteStop(new OperationFrames.RemoteStop(pileCode, gunNo)),
                        java.time.Duration.ofSeconds(15));
                OperationFrames.StopAck result = OperationFrames.decodeStopAck(ack.body());
                yield new OpsDtos.CommandResult(
                        result.success(),
                        result.success() ? "远程停机成功，等待交易记录" : "桩停机失败，原因=" + result.failReason(),
                        Map.of("gunNo", gunNo, "success", result.success()));
            }
            case "read-realtime" ->
                new OpsDtos.CommandResult(
                        true, "读取实时数据成功", Map.of("realtime", ykcCommands.readRealtime(pileCode, str(params, "gunNo"))));
            case "time-sync" ->
                new OpsDtos.CommandResult(
                        true,
                        "对时成功",
                        Map.of("time", ykcCommands.timeSync(pileCode).pileCode()));
            case "param-set" -> {
                var result = ykcCommands.paramSet(
                        pileCode, bool(params, "allowWork", true), intVal(params, "maxPowerPercent", 100));
                yield new OpsDtos.CommandResult(
                        result.success(), result.success() ? "参数设置成功" : "参数设置失败", Map.of("success", result.success()));
            }
            case "reboot" -> {
                var result = ykcCommands.reboot(pileCode, intVal(params, "control", 1));
                yield new OpsDtos.CommandResult(
                        result.success(), result.success() ? "重启命令已接受" : "重启失败", Map.of("success", result.success()));
            }
            case "update" -> {
                var update = new MaintenanceFrames.Update(
                        pileCode,
                        intVal(params, "pileModel", 1),
                        intVal(params, "power", 0),
                        str(params, "serverAddress"),
                        intVal(params, "port", 0),
                        str(params, "username"),
                        str(params, "password"),
                        str(params, "filePath"),
                        intVal(params, "control", 1),
                        intVal(params, "downloadTimeoutMinutes", 10));
                var result = ykcCommands.update(pileCode, update);
                yield new OpsDtos.CommandResult(
                        result.status() == 0, "升级命令状态=" + result.status(), Map.of("status", result.status()));
            }
            case "rate-model" -> {
                var result = ykcCommands.pushRateModel(pileCode);
                yield new OpsDtos.CommandResult(
                        result.success(),
                        result.success() ? "计费模型下发成功" : "计费模型下发失败",
                        Map.of("success", result.success()));
            }
            case "balance" -> {
                var result = ykcCommands.balance(
                        pileCode,
                        str(params, "gunNo"),
                        str(params, "physicalCard"),
                        decimal(params, "balance", new BigDecimal("1000.00")));
                yield new OpsDtos.CommandResult(
                        result.result() == 1, "余额更新结果=" + result.result(), Map.of("result", result.result()));
            }
            case "cards-sync" -> {
                var cards = toCards(params);
                var result = ykcCommands.cardSync(pileCode, cards);
                yield new OpsDtos.CommandResult(
                        result != null && result.success(),
                        "离线卡同步完成",
                        Map.of("success", result != null && result.success()));
            }
            case "cards-clear" -> {
                var result = ykcCommands.cardClear(pileCode, toStringList(params.get("cards")));
                yield new OpsDtos.CommandResult(
                        true, "离线卡清除完成", Map.of("count", result.results().size()));
            }
            case "cards-query" -> {
                var result = ykcCommands.cardQuery(pileCode, toStringList(params.get("cards")));
                yield new OpsDtos.CommandResult(
                        true, "离线卡查询完成", Map.of("count", result.results().size()));
            }
            case "lock" -> {
                var result = ykcCommands.lockControl(pileCode, intVal(params, "gunNo", 1), intVal(params, "action", 1));
                yield new OpsDtos.CommandResult(
                        result.success(), result.success() ? "地锁控制成功" : "地锁控制失败", Map.of("success", result.success()));
            }
            default -> new OpsDtos.CommandResult(false, "云快充不支持命令: " + request.type(), Map.of());
        };
    }

    private OpsDtos.CommandResult ocpp(OpsDtos.CommandRequest request) {
        String code = request.protocolDeviceId();
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        JsonNode reply;
        switch (request.type()) {
            case "remote-start" -> {
                int connectorId = intVal(params, "gunNo", 1);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("connectorId", connectorId);
                payload.put("idTag", str(params, "idTag") == null ? "IPE-APP" : str(params, "idTag"));
                reply = ocppCommands.rawCall(code, OcppConstants.REMOTE_START_TRANSACTION, payload);
                String status =
                        reply == null ? "Rejected" : reply.path("status").asString("Rejected");
                boolean accepted = OcppConstants.ACCEPTED.equals(status);
                return new OpsDtos.CommandResult(
                        accepted,
                        accepted ? "OCPP 远程启动已受理，等待设备 StartTransaction" : "设备拒绝远程启动: " + status,
                        Map.of("status", status));
            }
            case "remote-stop" -> {
                Integer transactionId = intVal(params, "transactionId", 0);
                reply = ocppCommands.rawCall(
                        code, OcppConstants.REMOTE_STOP_TRANSACTION, Map.of("transactionId", transactionId));
                String status =
                        reply == null ? "Rejected" : reply.path("status").asString("Rejected");
                boolean accepted = OcppConstants.ACCEPTED.equals(status);
                return new OpsDtos.CommandResult(
                        accepted,
                        accepted ? "OCPP 远程停机已受理，等待 StopTransaction" : "设备拒绝远程停机: " + status,
                        Map.of("status", status));
            }
            case "reset" -> {
                return result(ocppCommands.reset(code, str(params, "mode")), "重启命令已发送");
            }
            case "unlock" -> {
                return result(ocppCommands.unlockConnector(code, intVal(params, "connectorId", 1)), "解锁命令已发送");
            }
            case "configuration" -> {
                if ("GET".equalsIgnoreCase(str(params, "method"))) {
                    return new OpsDtos.CommandResult(
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
                        ocppCommands.changeAvailability(code, intVal(params, "connectorId", 0), str(params, "type")),
                        "可用性切换已发送");
            }
            case "trigger" -> {
                return result(
                        ocppCommands.triggerMessage(
                                code, str(params, "requestedMessage"), intVal(params, "connectorId", 0)),
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
                return new OpsDtos.CommandResult(false, "OCPP 不支持命令: " + request.type(), Map.of());
            }
        }
    }

    private static OpsDtos.CommandResult result(JsonNode reply, String successMessage) {
        return new OpsDtos.CommandResult(true, successMessage, Map.of("reply", reply == null ? Map.of() : reply));
    }

    private static String str(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
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
