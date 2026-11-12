package top.yealike.ipe.ops.diagnostic;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.yealike.ipe.protocol.codec.Frame;
import top.yealike.ipe.protocol.codec.FrameCodec;
import top.yealike.ipe.protocol.codec.FramePayloads;
import top.yealike.ipe.protocol.codec.FrameType;
import top.yealike.ipe.protocol.codec.TripleDes;
import top.yealike.ipe.protocol.ocpp16.codec.OcppMessage;
import top.yealike.ipe.protocol.ocpp16.codec.OcppMessageCodec;

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
        if (text.startsWith("[") || text.startsWith("{")) {
            return decodeOcpp(text);
        }
        return decodeYkc(text, protocolHint);
    }

    private Map<String, Object> decodeOcpp(String text) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol", "OCPP16J");
        try {
            OcppMessage message = OcppMessageCodec.decode(text);
            result.put("ok", true);
            result.put("messageTypeId", message.messageTypeId());
            result.put(
                    "messageType",
                    switch (message.messageTypeId()) {
                        case 2 -> "CALL";
                        case 3 -> "CALLRESULT";
                        case 4 -> "CALLERROR";
                        default -> "UNKNOWN";
                    });
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
}
