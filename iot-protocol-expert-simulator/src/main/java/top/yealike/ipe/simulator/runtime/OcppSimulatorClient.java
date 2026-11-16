package top.yealike.ipe.simulator.runtime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import top.yealike.ipe.protocol.ocpp16.codec.OcppConstants;
import top.yealike.ipe.protocol.ocpp16.codec.OcppMessage;
import top.yealike.ipe.protocol.ocpp16.codec.OcppMessageCodec;
import top.yealike.ipe.simulator.config.SimulatorProperties;

/** OCPP 1.6J WebSocket 客户端。使用 JDK WebSocket，避免在模拟器内再维护一套 Netty 握手流水线。 */
public class OcppSimulatorClient {

    private static final Logger log = LoggerFactory.getLogger(OcppSimulatorClient.class);
    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private final SimulatorProperties properties;
    private final SimulatorRuntime runtime;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, Integer> transactionIds = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> meterStartsWh = new ConcurrentHashMap<>();
    private final Map<String, String> configuration = new ConcurrentHashMap<>();
    private final AtomicLong uniqueIds = new AtomicLong(System.currentTimeMillis() % 100_000);

    private volatile WebSocket socket;
    private volatile boolean connected;

    public OcppSimulatorClient(SimulatorProperties properties, SimulatorRuntime runtime) {
        this.properties = properties;
        this.runtime = runtime;
        configuration.put("HeartbeatInterval", Integer.toString(properties.getHeartbeatSeconds()));
        configuration.put("MeterValueSampleInterval", Integer.toString(properties.getChargingRealtimeSeconds()));
        configuration.put("ConnectionTimeOut", "60");
        configuration.put("ResetRetries", "1");
        configuration.put("SupportedFeatureProfiles", "Core,FirmwareManagement,RemoteTrigger");
    }

    public boolean isConnected() {
        return connected && socket != null && !socket.isOutputClosed();
    }

    public boolean connect() {
        if (isConnected()) {
            return true;
        }
        String host = properties.getPlatformHost();
        String path =
                properties.getOcppPath() == null || properties.getOcppPath().isBlank()
                        ? "/ocpp"
                        : properties.getOcppPath();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        URI uri = URI.create("ws://" + host + ":" + properties.getOcppPort() + path + "/" + properties.getPileCode());
        try {
            WebSocket ws = http.newWebSocketBuilder()
                    .subprotocols(OcppConstants.SUB_PROTOCOL)
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(uri, new Listener())
                    .get(6, TimeUnit.SECONDS);
            this.socket = ws;
            this.connected = true;
            runtime.onOcppConnected(uri.toString());
            sendBootNotification();
            log.info("OCPP WebSocket 已连接 {}", uri);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            runtime.onOcppError("OCPP 连接被中断");
            return false;
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            runtime.onOcppError("OCPP 连接失败: " + cause.getMessage());
            log.warn("OCPP 连接 {} 失败: {}", uri, cause.getMessage());
            return false;
        }
    }

    public void disconnect() {
        connected = false;
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "simulator disconnect")
                        .get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                current.abort();
            }
        }
        pending.values().forEach(future -> future.completeExceptionally(new IllegalStateException("OCPP 连接已断开")));
        pending.clear();
    }

    // ------------------------------------------------------------------ 上行

    public void sendBootNotification() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chargePointVendor", "IPE");
        payload.put("chargePointModel", "IPE-SIM");
        payload.put("chargePointSerialNumber", properties.getPileCode());
        payload.put("firmwareVersion", properties.getProgramVersion());
        payload.put("iccid", properties.getSimCard());
        payload.put("imsi", "");
        payload.put("meterType", "AC");
        payload.put("meterSerialNumber", "IPE-METER-" + properties.getPileCode());
        call(OcppConstants.BOOT_NOTIFICATION, payload, true)
                .thenAccept(reply -> {
                    String status = text(reply, "status", "");
                    int interval = integer(reply, "interval", 300);
                    if (OcppConstants.ACCEPTED.equals(status)) {
                        runtime.onOcppBootAccepted(interval);
                        for (int i = 1; i <= properties.getGunCount(); i++) {
                            String gunNo = String.format("%02d", i);
                            statusNotification(i, runtime.gunRuntimeStatus(gunNo), runtime.gunFaultCode(gunNo));
                        }
                    } else {
                        runtime.onOcppError("OCPP BootNotification 被拒绝: " + status);
                    }
                })
                .exceptionally(error -> {
                    runtime.onOcppError("OCPP BootNotification 失败: " + rootMessage(error));
                    return null;
                });
    }

    public void heartbeat() {
        if (!isConnected()) {
            return;
        }
        call(OcppConstants.HEARTBEAT, Map.of(), false)
                .thenAccept(reply -> runtime.onOcppHeartbeatAck())
                .exceptionally(error -> {
                    runtime.onOcppError("OCPP Heartbeat 失败: " + rootMessage(error));
                    return null;
                });
    }

    public void statusNotification(int connectorId, String status, String errorCode) {
        if (!isConnected()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("connectorId", connectorId);
        payload.put("status", status);
        payload.put("errorCode", errorCode == null ? "NoError" : errorCode);
        payload.put("timestamp", Instant.now().toString());
        send(OcppConstants.STATUS_NOTIFICATION, payload);
    }

    public void meterValues(String gunNo, BigDecimal energyKwh, BigDecimal voltage, BigDecimal current, int soc) {
        if (!isConnected() || !transactionIds.containsKey(gunNo)) {
            return;
        }
        BigDecimal start = meterStartsWh.getOrDefault(gunNo, BigDecimal.ZERO);
        BigDecimal registerWh = start.add(energyKwh.multiply(THOUSAND));
        BigDecimal powerKw = voltage.multiply(current).divide(THOUSAND, 4, RoundingMode.HALF_UP);

        ObjectNode payload = OcppMessageCodec.object();
        payload.put("connectorId", connectorId(gunNo));
        payload.put("transactionId", transactionIds.get(gunNo));
        ArrayNode meterValue = OcppMessageCodec.array();
        ObjectNode sample = OcppMessageCodec.object();
        sample.put("timestamp", Instant.now().toString());
        ArrayNode values = OcppMessageCodec.array();
        values.add(sampled("Energy.Active.Import.Register", registerWh.setScale(3, RoundingMode.HALF_UP), "Wh"));
        values.add(sampled("Power.Active.Import", powerKw, "kW"));
        values.add(sampled("Voltage", voltage, "V"));
        values.add(sampled("Current.Import", current, "A"));
        values.add(sampled("SoC", BigDecimal.valueOf(soc), "Percent"));
        sample.set("sampledValue", values);
        meterValue.add(sample);
        payload.set("meterValue", meterValue);
        sendNode(OcppConstants.METER_VALUES, payload);
    }

    public void startTransaction(String gunNo, String idTag, int startType) {
        if (!isConnected()) {
            runtime.onOcppStartRejected(gunNo, "OCPP 未连接");
            return;
        }
        BigDecimal meterStart = runtime.ocppMeterWh(gunNo);
        Map<String, Object> authorize = Map.of("idTag", idTag == null ? "" : idTag);
        call(OcppConstants.AUTHORIZE, authorize, false)
                .thenAccept(reply -> {
                    JsonNode idTagInfo = reply == null ? null : reply.get("idTagInfo");
                    String status = idTagInfo == null ? "" : text(idTagInfo, "status", "");
                    if (!OcppConstants.ACCEPTED.equals(status)) {
                        runtime.onOcppStartRejected(gunNo, "Authorize 未通过: " + status);
                        return;
                    }
                    Map<String, Object> start = new LinkedHashMap<>();
                    start.put("connectorId", connectorId(gunNo));
                    start.put("idTag", idTag == null ? "" : idTag);
                    start.put("meterStart", meterStart);
                    start.put("timestamp", Instant.now().toString());
                    call(OcppConstants.START_TRANSACTION, start, false)
                            .thenAccept(startReply -> {
                                int transactionId = integer(startReply, "transactionId", -1);
                                String startStatus =
                                        startReply == null ? "" : text(startReply.get("idTagInfo"), "status", "");
                                if (transactionId > 0 && OcppConstants.ACCEPTED.equals(startStatus)) {
                                    transactionIds.put(gunNo, transactionId);
                                    meterStartsWh.put(gunNo, meterStart);
                                    runtime.onOcppTransactionStarted(gunNo, transactionId, startType);
                                } else {
                                    runtime.onOcppStartRejected(gunNo, "StartTransaction 未通过: " + startStatus);
                                }
                            })
                            .exceptionally(error -> {
                                runtime.onOcppStartRejected(gunNo, rootMessage(error));
                                return null;
                            });
                })
                .exceptionally(error -> {
                    runtime.onOcppStartRejected(gunNo, rootMessage(error));
                    return null;
                });
    }

    public void stopTransaction(String gunNo, BigDecimal energyKwh, int reason) {
        Integer transactionId = transactionIds.remove(gunNo);
        BigDecimal start = meterStartsWh.remove(gunNo);
        if (transactionId == null || !isConnected()) {
            return;
        }
        BigDecimal meterStop = (start == null ? BigDecimal.ZERO : start).add(energyKwh.multiply(THOUSAND));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId", transactionId);
        payload.put("meterStop", meterStop.setScale(3, RoundingMode.HALF_UP));
        payload.put("timestamp", Instant.now().toString());
        payload.put("reason", mapStopReason(reason));
        call(OcppConstants.STOP_TRANSACTION, payload, false)
                .thenAccept(reply -> log.info("OCPP 交易 {} StopTransaction 已应答", transactionId))
                .exceptionally(error -> {
                    runtime.onOcppError("OCPP StopTransaction 失败: " + rootMessage(error));
                    return null;
                });
    }

    public void clearTransactions() {
        transactionIds.clear();
        meterStartsWh.clear();
    }

    private ObjectNode sampled(String measurand, BigDecimal value, String unit) {
        ObjectNode node = OcppMessageCodec.object();
        node.put("value", value.toPlainString());
        node.put("measurand", measurand);
        node.put("unit", unit);
        return node;
    }

    // ------------------------------------------------------------------ 下行

    private void handle(String text) {
        OcppMessage message;
        try {
            message = OcppMessageCodec.decode(text);
        } catch (RuntimeException e) {
            runtime.recordOcppLog("IN", "CALLERROR", "Decode", text, "消息解析失败: " + e.getMessage());
            log.warn("OCPP 下行消息解析失败: {}", e.getMessage());
            return;
        }
        runtime.recordOcppLog(
                "IN",
                message.typeLabel(),
                message.action(),
                text,
                message.action() == null ? message.typeLabel() : message.action() + " 下行");
        if (message.isCall()) {
            handleCall(message);
            return;
        }
        CompletableFuture<JsonNode> future = pending.remove(message.uniqueId());
        if (future != null) {
            future.complete(message.isError() ? errorNode(message) : message.payload());
        }
    }

    private void handleCall(OcppMessage message) {
        String action = message.action();
        JsonNode payload = message.payload() == null ? OcppMessageCodec.object() : message.payload();
        try {
            switch (action) {
                case OcppConstants.REMOTE_START_TRANSACTION -> handleRemoteStart(message, payload);
                case OcppConstants.REMOTE_STOP_TRANSACTION -> handleRemoteStop(message, payload);
                case OcppConstants.RESET -> {
                    boolean hard = "Hard".equalsIgnoreCase(text(payload, "type", "Soft"));
                    result(message, Map.of("status", OcppConstants.ACCEPTED));
                    runtime.ocppReset(hard);
                }
                case OcppConstants.UNLOCK_CONNECTOR -> {
                    String status = runtime.ocppUnlock(integer(payload, "connectorId", 1));
                    result(message, Map.of("status", status));
                }
                case OcppConstants.CHANGE_CONFIGURATION -> {
                    String key = text(payload, "key", null);
                    String value = text(payload, "value", "");
                    if (key == null || key.isBlank()) {
                        result(message, Map.of("status", OcppConstants.REJECTED));
                    } else {
                        configuration.put(key, value);
                        result(message, Map.of("status", OcppConstants.ACCEPTED));
                    }
                }
                case OcppConstants.GET_CONFIGURATION -> handleGetConfiguration(message, payload);
                case OcppConstants.CHANGE_AVAILABILITY -> {
                    int connectorId = integer(payload, "connectorId", 0);
                    boolean operative = !"Inoperative".equalsIgnoreCase(text(payload, "type", "Operative"));
                    runtime.ocppSetAvailability(connectorId, operative);
                    result(message, Map.of("status", OcppConstants.ACCEPTED));
                }
                case OcppConstants.TRIGGER_MESSAGE -> {
                    String requested = text(payload, "requestedMessage", "");
                    Integer connectorId = payload.has("connectorId") ? integer(payload, "connectorId", 0) : null;
                    result(message, Map.of("status", OcppConstants.ACCEPTED));
                    runtime.ocppTrigger(requested, connectorId);
                }
                case OcppConstants.CLEAR_CACHE -> {
                    configuration.clear();
                    result(message, Map.of("status", OcppConstants.ACCEPTED));
                }
                case OcppConstants.GET_DIAGNOSTICS ->
                    result(message, Map.of("fileName", "diagnostics-" + properties.getPileCode() + ".log"));
                case OcppConstants.UPDATE_FIRMWARE -> {
                    runtime.ocppFirmwareUpdate(text(payload, "location", ""));
                    result(message, Map.of());
                }
                case OcppConstants.DATA_TRANSFER ->
                    result(message, Map.of("status", OcppConstants.ACCEPTED, "data", ""));
                default -> error(message, OcppConstants.ERROR_NOT_IMPLEMENTED, "模拟器未实现动作: " + action);
            }
        } catch (RuntimeException e) {
            log.warn("处理 OCPP 下行动作 {} 失败", action, e);
            error(message, OcppConstants.ERROR_INTERNAL, e.getMessage());
        }
    }

    private void handleRemoteStart(OcppMessage message, JsonNode payload) {
        int connectorId = integer(payload, "connectorId", 0);
        String idTag = text(payload, "idTag", "IPE-APP");
        String gunNo = String.format("%02d", connectorId);
        if (!runtime.ocppCanStart(gunNo)) {
            result(message, Map.of("status", OcppConstants.REJECTED));
            return;
        }
        runtime.ocppMarkAuthorizing(gunNo);
        result(message, Map.of("status", OcppConstants.ACCEPTED));
        startTransaction(gunNo, idTag, 1);
    }

    private void handleRemoteStop(OcppMessage message, JsonNode payload) {
        int transactionId = integer(payload, "transactionId", -1);
        String gunNo = transactionIds.entrySet().stream()
                .filter(entry -> entry.getValue() == transactionId)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        if (gunNo == null || !runtime.ocppCanStop(gunNo)) {
            result(message, Map.of("status", OcppConstants.REJECTED));
            return;
        }
        result(message, Map.of("status", OcppConstants.ACCEPTED));
        runtime.ocppRemoteStop(gunNo);
    }

    private void handleGetConfiguration(OcppMessage message, JsonNode payload) {
        ObjectNode response = OcppMessageCodec.object();
        ArrayNode keys = OcppMessageCodec.array();
        JsonNode requested = payload.get("key");
        if (requested != null && requested.isArray() && !requested.isEmpty()) {
            for (JsonNode keyNode : requested) {
                String key = keyNode.asString();
                if (configuration.containsKey(key)) {
                    ObjectNode item = OcppMessageCodec.object();
                    item.put("key", key);
                    item.put("readonly", false);
                    item.put("value", configuration.get(key));
                    keys.add(item);
                }
            }
        } else {
            configuration.forEach((key, value) -> {
                ObjectNode item = OcppMessageCodec.object();
                item.put("key", key);
                item.put("readonly", false);
                item.put("value", value);
                keys.add(item);
            });
        }
        response.set("configurationKey", keys);
        resultNode(message, response);
    }

    // ------------------------------------------------------------------ 工具

    private CompletableFuture<JsonNode> call(String action, Object payload, boolean required) {
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        if (!isConnected()) {
            future.completeExceptionally(new IllegalStateException("OCPP 未连接"));
            return future;
        }
        String uniqueId = "sim-" + Long.toString(uniqueIds.incrementAndGet(), 36);
        pending.put(uniqueId, future);
        String text = OcppMessageCodec.encode(OcppMessage.call(uniqueId, action, OcppMessageCodec.value(payload)));
        runtime.recordOcppLog("OUT", "CALL", action, text, action + " 上行");
        socket.sendText(text, true).whenComplete((ignored, error) -> {
            if (error != null) {
                pending.remove(uniqueId);
                future.completeExceptionally(error);
            }
        });
        if (!required) {
            future.orTimeout(30, TimeUnit.SECONDS).whenComplete((ignored, error) -> pending.remove(uniqueId));
        }
        return future;
    }

    private void send(String action, Object payload) {
        sendNode(action, OcppMessageCodec.value(payload));
    }

    private void sendNode(String action, JsonNode payload) {
        if (!isConnected()) {
            return;
        }
        String uniqueId = "sim-" + Long.toString(uniqueIds.incrementAndGet(), 36);
        String text = OcppMessageCodec.encode(OcppMessage.call(uniqueId, action, payload));
        runtime.recordOcppLog("OUT", "CALL", action, text, action + " 上行");
        socket.sendText(text, true).exceptionally(error -> {
            runtime.onOcppError(action + " 发送失败: " + error.getMessage());
            return null;
        });
    }

    private void result(OcppMessage request, Object payload) {
        resultNode(request, OcppMessageCodec.value(payload));
    }

    private void resultNode(OcppMessage request, JsonNode payload) {
        String text = OcppMessageCodec.encode(OcppMessage.result(request.uniqueId(), payload));
        runtime.recordOcppLog("OUT", "CALLRESULT", request.action(), text, request.action() + " 应答");
        socket.sendText(text, true);
    }

    private void error(OcppMessage request, String code, String description) {
        String text = OcppMessageCodec.encode(OcppMessage.error(
                request.uniqueId(), code, description == null ? "" : description, OcppMessageCodec.object()));
        runtime.recordOcppLog("OUT", "CALLERROR", request.action(), text, code);
        socket.sendText(text, true);
    }

    private JsonNode errorNode(OcppMessage message) {
        ObjectNode node = OcppMessageCodec.object();
        node.put("errorCode", message.errorCode());
        node.put("errorDescription", message.errorDescription());
        return node;
    }

    private int connectorId(String gunNo) {
        try {
            return Integer.parseInt(gunNo);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String mapStopReason(int reason) {
        return switch (reason) {
            case 0x40 -> "Remote";
            case 0x72 -> "EmergencyStop";
            case 0x83 -> "PowerLoss";
            default -> "Local";
        };
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return fallback;
        }
        String value = node.get(field).asString();
        return value == null ? fallback : value;
    }

    private static int integer(JsonNode node, String field, int fallback) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return fallback;
        }
        return node.get(field).asInt(fallback);
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private final class Listener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                try {
                    handle(message);
                } catch (RuntimeException e) {
                    log.warn("处理 OCPP 下行消息失败: {}", e.getMessage(), e);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, java.nio.ByteBuffer message) {
            webSocket.sendPong(message);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (socket == webSocket || socket == null) {
                connected = false;
                socket = null;
                runtime.onOcppDisconnected("通道关闭 " + statusCode + " " + reason);
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (socket == webSocket || socket == null) {
                connected = false;
                socket = null;
                runtime.onOcppDisconnected("通道异常: " + rootMessage(error));
            }
        }
    }
}
