package top.yealike.pep.simulator.domain;

import java.time.LocalDateTime;
import java.util.Map;
import top.yealike.pep.codec.FrameCodec;
import top.yealike.pep.codec.FramePayloads;
import top.yealike.pep.codec.FrameType;

/** 模拟器与平台之间的协议帧日志。 */
public record FrameLog(
        LocalDateTime time,
        String direction,
        String label,
        String frameTypeHex,
        int sequence,
        String bodyHex,
        String summary) {
    public Object getDecoded() {
        if (frameTypeHex != null && !frameTypeHex.startsWith("0x")) {
            return Map.of(
                    "protocol", "OCPP16J", "messageType", frameTypeHex, "payload", bodyHex == null ? "" : bodyHex);
        }
        try {
            return FramePayloads.decode(FrameType.of(Integer.decode(frameTypeHex)), FrameCodec.parseHex(bodyHex));
        } catch (RuntimeException e) {
            return Map.of("error", e.getMessage());
        }
    }
}
