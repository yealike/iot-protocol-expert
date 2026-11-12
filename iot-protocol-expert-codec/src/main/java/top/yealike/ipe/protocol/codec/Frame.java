package top.yealike.ipe.protocol.codec;

import java.util.Arrays;

/**
 * 一帧应用层报文。
 *
 * <p>帧结构：0x68 | 数据长度(1) | 序列号(2, 低字节在前) | 加密标志(1) | 帧类型(1) | 消息体 | CRC16(2, 低字节在前)。 数据长度 = 序列号 +
 * 加密标志 + 帧类型 + 消息体 的字节数，不含起始符与 CRC。
 */
public record Frame(int sequence, boolean encrypted, int frameType, byte[] body) {

    public Frame {
        if (sequence < 0 || sequence > 0xFFFF) {
            throw new ProtocolException("序列号必须在 0~65535");
        }
        body = body == null ? new byte[0] : body.clone();
        if (4 + body.length > 200) {
            throw new ProtocolException("数据长度超过 200 字节: " + (4 + body.length));
        }
    }

    public FrameType type() {
        return FrameType.of(frameType);
    }

    public boolean isUp() {
        return type().direction() == FrameType.Direction.UP;
    }

    public String bodyHex() {
        return java.util.HexFormat.of().withUpperCase().formatHex(body);
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Frame f
                && sequence == f.sequence
                && encrypted == f.encrypted
                && frameType == f.frameType
                && Arrays.equals(body, f.body);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * sequence + Boolean.hashCode(encrypted)) + frameType) + Arrays.hashCode(body);
    }

    @Override
    public String toString() {
        return "Frame[seq="
                + sequence
                + ", type="
                + type().hex()
                + " "
                + type().label()
                + ", encrypted="
                + encrypted
                + ", body="
                + bodyHex()
                + "]";
    }
}
