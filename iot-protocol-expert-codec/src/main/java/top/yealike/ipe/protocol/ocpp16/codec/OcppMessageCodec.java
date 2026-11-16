package top.yealike.ipe.protocol.ocpp16.codec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** OCPP 1.6J JSON 数组编解码。 */
public final class OcppMessageCodec {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private OcppMessageCodec() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static OcppMessage decode(String text) {
        if (text == null || text.isBlank()) {
            throw new OcppException("FormationViolation", "空消息");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(text);
        } catch (RuntimeException e) {
            throw new OcppException("FormationViolation", "消息不是合法 JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isArray() || root.size() < 2) {
            throw new OcppException("FormationViolation", "OCPP 消息必须是数组");
        }
        JsonNode typeNode = root.get(0);
        if (typeNode == null || !typeNode.isNumber()) {
            throw new OcppException("FormationViolation", "MessageTypeId 必须是整数");
        }
        int type = typeNode.asInt();
        if (type != OcppMessage.CALL && type != OcppMessage.CALL_RESULT && type != OcppMessage.CALL_ERROR) {
            throw new OcppException("MessageTypeNotSupported", "不支持的 MessageTypeId: " + type);
        }
        String uniqueId = text(root.get(1));
        if (uniqueId == null || uniqueId.isBlank()) {
            throw new OcppException("FormationViolation", "UniqueId 不能为空");
        }
        return switch (type) {
            case OcppMessage.CALL -> {
                if (root.size() < 4) {
                    throw new OcppException("FormationViolation", "CALL 必须包含 action 与 payload");
                }
                String action = text(root.get(2));
                if (action == null || action.isBlank()) {
                    throw new OcppException("FormationViolation", "Action 不能为空");
                }
                JsonNode payload = root.get(3);
                yield OcppMessage.call(uniqueId, action, payload == null ? MAPPER.createObjectNode() : payload);
            }
            case OcppMessage.CALL_RESULT ->
                OcppMessage.result(
                        uniqueId, root.size() > 2 && root.get(2) != null ? root.get(2) : MAPPER.createObjectNode());
            case OcppMessage.CALL_ERROR -> {
                String code = root.size() > 2 ? text(root.get(2)) : "InternalError";
                String description = root.size() > 3 ? text(root.get(3)) : "";
                JsonNode details = root.size() > 4 ? root.get(4) : MAPPER.createObjectNode();
                yield OcppMessage.error(uniqueId, code == null ? "InternalError" : code, description, details);
            }
            default -> throw new OcppException("MessageTypeNotSupported", "不支持的 MessageTypeId: " + type);
        };
    }

    public static String encode(OcppMessage message) {
        ArrayNode array = MAPPER.createArrayNode();
        array.add(message.messageTypeId());
        array.add(message.uniqueId());
        switch (message.messageTypeId()) {
            case OcppMessage.CALL -> {
                array.add(message.action());
                array.add(message.payload() == null ? MAPPER.createObjectNode() : message.payload());
            }
            case OcppMessage.CALL_RESULT ->
                array.add(message.payload() == null ? MAPPER.createObjectNode() : message.payload());
            case OcppMessage.CALL_ERROR -> {
                array.add(message.errorCode());
                array.add(message.errorDescription() == null ? "" : message.errorDescription());
                array.add(message.payload() == null ? MAPPER.createObjectNode() : message.payload());
            }
            default ->
                throw new OcppException("MessageTypeNotSupported", "不支持的 MessageTypeId: " + message.messageTypeId());
        }
        return MAPPER.writeValueAsString(array);
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode array() {
        return MAPPER.createArrayNode();
    }

    public static JsonNode value(Object value) {
        return value == null ? MAPPER.createObjectNode() : MAPPER.valueToTree(value);
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }
}
