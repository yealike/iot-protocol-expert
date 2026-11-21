package top.yealike.pep.ops.diagnostic;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import top.yealike.pep.codec.Frame;
import top.yealike.pep.codec.FrameCodec;
import top.yealike.pep.codec.FramePayloads;
import top.yealike.pep.codec.FrameType;
import top.yealike.pep.codec.TripleDes;
import top.yealike.pep.codec.ocpp16.OcppMessage;
import top.yealike.pep.codec.ocpp16.OcppMessageCodec;

/** 两个协议的统一报文释义入口：云快充 HEX / OCPP JSON。 */
@Service
public class FrameDecodeService {

    private final byte[] key;

    public FrameDecodeService(@Value("${ipe.codec.crypto-key-hex:0123456789ABCDEFFEDCBA9876543210}") String keyHex) {
        this.key = TripleDes.keyFromHex(keyHex);
    }

    public Map<String, Object> decode(String raw, String protocolHint) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            result.put("ok", false);
            result.put("error", "报文为空");
            return result;
        }
        String text = raw.trim();
        if (text.startsWith("[")) {
            return decodeOcpp(text);
        }
        String normalized = protocolHint == null ? "" : protocolHint.trim().toUpperCase(java.util.Locale.ROOT);
        if (text.startsWith("{")
                && ("OCPP16J".equals(normalized) || "OCPP16".equals(normalized) || "OCPP1.6J".equals(normalized))) {
            return decodeOcppPayload(OcppMessage.CALL, text, null, null, null);
        }
        return decodeYkc(text, protocolHint);
    }

    /**
     * 解码 Ops 中已持久化的帧体。云快充使用已解密 bodyHex + frameType，避免历史帧受当前 3DES 密钥变化影响；
     * OCPP 的 bodyHex 实际保存 payload JSON。
     */
    public Map<String, Object> decodeStored(
            String protocol, int frameType, String payload, String frameLabel, String messageId, String storedError) {
        String normalized = protocol == null ? "" : protocol.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "YKC16", "YKC", "YKC1.6" -> decodeYkcBody(frameType, payload, storedError);
            case "OCPP16J", "OCPP16", "OCPP1.6J" ->
                decodeOcppPayload(frameType, payload, frameLabel, messageId, storedError);
            default -> failure(normalized, "不支持的设备协议: " + protocol);
        };
    }

    public Map<String, Object> decodeYkcBody(int frameType, String bodyHex, String storedError) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol", "YKC16");
        result.put("frameType", String.format("0x%02X", frameType));
        FrameType type = FrameType.ofOrNull(frameType);
        result.put("frameLabel", type == null ? "未知帧" : type.label());
        if (storedError != null && !storedError.isBlank()) {
            result.put("storedError", storedError);
        }
        if (type == null) {
            result.put("ok", false);
            result.put("error", "未注册的云快充帧类型: " + String.format("0x%02X", frameType));
            return result;
        }
        if (bodyHex == null || bodyHex.isBlank()) {
            result.put("ok", false);
            result.put("error", "帧体为空");
            return result;
        }
        try {
            byte[] body = FrameCodec.parseHex(bodyHex.replace(" ", ""));
            result.put("bodyLength", body.length);
            result.put("fields", FramePayloads.decode(type, body));
            result.put("ok", true);
            result.put("diagnosis", "使用持久化的已解密帧体和帧类型解析，字段按云快充 1.6 定义组织。");
        } catch (RuntimeException exception) {
            result.put("ok", false);
            result.put("error", "云快充帧体解析失败: " + exception.getMessage());
        }
        return result;
    }

    public Map<String, Object> decodeOcppPayload(
            int frameType, String payload, String frameLabel, String messageId, String storedError) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol", "OCPP16J");
        result.put("messageTypeId", frameType);
        result.put("messageType", ocppType(frameType));
        result.put("uniqueId", messageId);
        if (frameLabel != null && !frameLabel.isBlank()) {
            result.put("action", frameLabel);
        }
        if (storedError != null && !storedError.isBlank()) {
            result.put("storedError", storedError);
        }
        if (payload == null || payload.isBlank()) {
            result.put("ok", false);
            result.put("error", "OCPP payload 为空");
            return result;
        }
        String text = payload.trim();
        if (text.startsWith("[")) {
            return decodeOcpp(text);
        }
        try {
            JsonNode value = OcppMessageCodec.mapper().readTree(text);
            result.put("fields", value);
            result.put("ok", true);
            result.put(
                    "diagnosis",
                    frameType == OcppMessage.CALL_RESULT
                            ? "CALLRESULT 只保存结果 payload，Action 需按 uniqueId 关联同连接上的 CALL。"
                            : "使用持久化的 OCPP payload JSON 解析。");
        } catch (RuntimeException exception) {
            // OCPP CALLERROR 的 body 可能是错误描述而不是 JSON。
            if (frameType == OcppMessage.CALL_ERROR) {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("description", text);
                fields.put("errorCode", storedError);
                result.put("fields", fields);
                result.put("ok", true);
            } else {
                result.put("ok", false);
                result.put("error", "OCPP payload JSON 解析失败: " + exception.getMessage());
            }
        }
        return result;
    }

    private Map<String, Object> decodeOcpp(String text) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol", "OCPP16J");
        try {
            OcppMessage message = OcppMessageCodec.decode(text);
            result.put("ok", true);
            result.put("messageTypeId", message.messageTypeId());
            result.put("messageType", ocppType(message.messageTypeId()));
            result.put("uniqueId", message.uniqueId());
            result.put("action", message.action());
            result.put("payload", message.payload());
            if (message.errorCode() != null) {
                result.put("errorCode", message.errorCode());
                result.put("errorDescription", message.errorDescription());
            }
            result.put(
                    "diagnosis",
                    message.messageTypeId() == 3
                            ? "应答帧只包含消息编号和结果；CALLRESULT 无 Action，需按 uniqueId 关联同一连接上的请求。"
                            : "字段按 OCPP 1.6J 消息定义解析。");
        } catch (RuntimeException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    private static String ocppType(int messageTypeId) {
        return switch (messageTypeId) {
            case OcppMessage.CALL -> "CALL";
            case OcppMessage.CALL_RESULT -> "CALLRESULT";
            case OcppMessage.CALL_ERROR -> "CALLERROR";
            default -> "UNKNOWN";
        };
    }

    private Map<String, Object> decodeYkc(String hex, String protocolHint) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol", "YKC16");
        try {
            byte[] data = FrameCodec.parseHex(hex.replace(" ", ""));
            Frame frame;
            try {
                frame = FrameCodec.decode(data, key);
                result.put("frameEncoding", "FULL_FRAME");
            } catch (RuntimeException fullFrameFailure) {
                // 允许只贴帧体：按 0x68 起头判断失败后，交给调用方补充帧类型。
                result.put("ok", false);
                result.put("error", "无法按完整云快充帧解析: " + fullFrameFailure.getMessage());
                result.put("rawLength", data.length);
                return result;
            }
            FrameType type = FrameType.ofOrNull(frame.frameType());
            result.put("frameEncoding", "FULL_FRAME");
            result.put("sequence", frame.sequence());
            result.put("encrypted", frame.encrypted());
            result.put("frameType", String.format("0x%02X", frame.frameType()));
            result.put("frameLabel", type == null ? "未知帧" : type.label());
            result.put("bodyHex", FrameCodec.hex(frame.body()));
            if (type != null) {
                Object decoded = FramePayloads.decode(type, frame.body());
                result.put("fields", decoded);
            }
            result.put("ok", true);
            result.put("diagnosis", type == null ? "帧类型未注册，只能呈现原始字段。" : "校验和通过，字段按云快充 1.6 定义解析。");
        } catch (RuntimeException e) {
            result.put("ok", false);
            result.put("error", "HEX 报文解析失败: " + e.getMessage());
        }
        return result;
    }

    private static Map<String, Object> failure(String protocol, String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol", protocol);
        result.put("ok", false);
        result.put("error", error);
        return result;
    }
}
