package top.yealike.ipe.protocol.ocpp16;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import top.yealike.ipe.protocol.config.ProtocolProperties;
import top.yealike.ipe.protocol.ocpp16.codec.OcppConstants;
import top.yealike.ipe.protocol.ocpp16.codec.OcppMessageCodec;
import top.yealike.ipe.protocol.ops.OpsClient;

/** OCPP 1.6J 常用运维命令。设备档案与在线状态由运维平台维护。 */
@Service
public class OcppCommandFacade {

    private final OcppSessionRegistry sessions;
    private final OcppConfigurationStore configurationStore;
    private final OpsClient ops;
    private final ProtocolProperties properties;

    public OcppCommandFacade(
            OcppSessionRegistry sessions,
            OcppConfigurationStore configurationStore,
            OpsClient ops,
            ProtocolProperties properties) {
        this.sessions = sessions;
        this.configurationStore = configurationStore;
        this.ops = ops;
        this.properties = properties;
    }

    public JsonNode reset(String pileCode, String type) {
        String resetType = "Hard".equalsIgnoreCase(type) ? "Hard" : "Soft";
        return call(pileCode, OcppConstants.RESET, Map.of("type", resetType));
    }

    public JsonNode unlockConnector(String pileCode, int connectorId) {
        return call(pileCode, OcppConstants.UNLOCK_CONNECTOR, Map.of("connectorId", connectorId));
    }

    public JsonNode changeConfiguration(String pileCode, String key, String value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("配置键不能为空");
        }
        JsonNode reply = call(
                pileCode, OcppConstants.CHANGE_CONFIGURATION, Map.of("key", key, "value", value == null ? "" : value));
        String status = reply == null ? "" : text(reply, "status", "");
        if (OcppConstants.ACCEPTED.equals(status)
                || OcppConstants.REJECTED.equals(status)
                || OcppConstants.NOT_SUPPORTED.equals(status)) {
            configurationStore.set(pileCode, key, value == null ? "" : value);
        }
        return reply;
    }

    public JsonNode getConfiguration(String pileCode, List<String> keys) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (keys != null && !keys.isEmpty()) {
            payload.put("key", keys);
        }
        JsonNode reply = call(pileCode, OcppConstants.GET_CONFIGURATION, payload);
        Map<String, String> local = configurationStore.get(pileCode);
        ObjectNode merged = OcppMessageCodec.object();
        ArrayNode configurationKey = OcppMessageCodec.array();
        local.forEach((key, value) -> {
            ObjectNode item = OcppMessageCodec.object();
            item.put("key", key);
            item.put("readonly", configurationStore.isReadonly(key));
            item.put("value", value);
            configurationKey.add(item);
        });
        merged.set("configurationKey", configurationKey);
        if (reply != null && reply.has("unknownKey")) {
            merged.set("unknownKey", reply.get("unknownKey"));
        }
        return merged;
    }

    public JsonNode changeAvailability(String pileCode, int connectorId, String type) {
        String availability = "Inoperative".equalsIgnoreCase(type) ? "Inoperative" : "Operative";
        return call(
                pileCode, OcppConstants.CHANGE_AVAILABILITY, Map.of("connectorId", connectorId, "type", availability));
    }

    public JsonNode triggerMessage(String pileCode, String requestedMessage, Integer connectorId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestedMessage", requestedMessage);
        if (connectorId != null && connectorId > 0) {
            payload.put("connectorId", connectorId);
        }
        return call(pileCode, OcppConstants.TRIGGER_MESSAGE, payload);
    }

    public JsonNode clearCache(String pileCode) {
        return call(pileCode, OcppConstants.CLEAR_CACHE, Map.of());
    }

    public JsonNode dataTransfer(String pileCode, String vendorId, String messageId, String data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vendorId", vendorId);
        if (messageId != null) {
            payload.put("messageId", messageId);
        }
        if (data != null) {
            payload.put("data", data);
        }
        return call(pileCode, OcppConstants.DATA_TRANSFER, payload);
    }

    public JsonNode getDiagnostics(String pileCode, String location, Integer retries, Integer retryInterval) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("location", location);
        if (retries != null) {
            payload.put("retries", retries);
        }
        if (retryInterval != null) {
            payload.put("retryInterval", retryInterval);
        }
        return call(pileCode, OcppConstants.GET_DIAGNOSTICS, payload);
    }

    public JsonNode updateFirmware(String pileCode, String location, String retrieveDate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("location", location);
        payload.put("retrieveDate", retrieveDate);
        return call(pileCode, OcppConstants.UPDATE_FIRMWARE, payload);
    }

    public JsonNode rawCall(String pileCode, String action, Map<String, Object> payload) {
        return call(pileCode, action, payload == null ? Map.of() : payload);
    }

    public JsonNode status(String pileCode) {
        ObjectNode node = OcppMessageCodec.object();
        node.put("pileCode", pileCode);
        node.put("protocol", OcppProtocol.PROTOCOL_CODE);
        node.put("online", sessions.isOnline(pileCode));
        try {
            var device = ops.resolve("OCPP16J", pileCode);
            if (device != null) {
                node.put("gunCount", device.gunCount());
                node.put("vendor", device.chargePointVendor() == null ? "" : device.chargePointVendor());
                node.put("model", device.chargePointModel() == null ? "" : device.chargePointModel());
            }
        } catch (RuntimeException ignored) {
        }
        ArrayNode keys = OcppMessageCodec.array();
        configurationStore.get(pileCode).forEach((key, value) -> keys.add(key));
        node.set("configurationKeys", keys);
        return node;
    }

    private JsonNode call(String pileCode, String action, Map<String, Object> payload) {
        if (!sessions.isOnline(pileCode)) {
            throw new IllegalStateException("OCPP 桩不在线: " + pileCode);
        }
        return sessions.call(pileCode, action, payload, timeout());
    }

    private Duration timeout() {
        return Duration.ofSeconds(Math.max(properties.getPendingTimeoutSeconds(), 3));
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return fallback;
        }
        String value = node.get(field).asString();
        return value == null ? fallback : value;
    }
}
