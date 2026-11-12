package top.yealike.ipe.protocol.codec;

import java.util.HexFormat;

/** 帧级编解码。只负责信封（长度、序列号、加密、CRC），不解释消息体。 */
public final class FrameCodec {

    public static final int START = 0x68;
    public static final int MAX_DATA_LENGTH = 200;

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private FrameCodec() {}

    /**
     * 解出一帧。加密帧会被解密为明文消息体。
     *
     * @param data 完整帧字节
     * @param key 3DES 密钥；为 null 时遇到加密帧抛异常
     */
    public static Frame decode(byte[] data, byte[] key) {
        if (data == null || data.length < 8) {
            throw new ProtocolException("帧长度不足 8 字节: " + (data == null ? 0 : data.length));
        }
        if ((data[0] & 0xFF) != START) {
            throw new ProtocolException("起始符必须为 0x68，实际 0x" + String.format("%02X", data[0] & 0xFF));
        }
        int length = data[1] & 0xFF;
        if (length < 4 || length > MAX_DATA_LENGTH) {
            throw new ProtocolException("数据长度非法: " + length);
        }
        int total = length + 4;
        if (data.length != total) {
            throw new ProtocolException("帧长不匹配，声明 " + total + " 字节，实际 " + data.length + " 字节");
        }
        int sequence = (data[2] & 0xFF) | ((data[3] & 0xFF) << 8);
        int encryption = data[4] & 0xFF;
        int frameType = data[5] & 0xFF;
        int expectedCrc = Crc16.fromWire(data[total - 2], data[total - 1]);
        int actualCrc = Crc16.compute(data, 2, length);
        if (expectedCrc != actualCrc) {
            throw new ProtocolException(String.format("CRC 校验失败，报文 %04X，计算 %04X", expectedCrc, actualCrc));
        }
        byte[] body = java.util.Arrays.copyOfRange(data, 6, total - 2);
        boolean encrypted = false;
        if (encryption == 0x01) {
            if (key == null || key.length == 0) {
                throw new ProtocolException("收到加密帧，但未配置 3DES 密钥");
            }
            body = TripleDes.decrypt(body, key);
            encrypted = true;
        } else if (encryption != 0x00) {
            throw new ProtocolException("未知加密标志: 0x" + String.format("%02X", encryption));
        }
        return new Frame(sequence, encrypted, frameType, body);
    }

    /** 编码为完整帧。encrypted=true 时使用 3DES 加密消息体。 */
    public static byte[] encode(Frame frame, boolean encrypted, byte[] key) {
        byte[] body = frame.body();
        int encryption = 0x00;
        if (encrypted) {
            if (key == null || key.length == 0) {
                throw new ProtocolException("要求加密发送，但未配置 3DES 密钥");
            }
            body = TripleDes.encrypt(body, key);
            encryption = 0x01;
        }
        int length = 4 + body.length;
        if (length > MAX_DATA_LENGTH) {
            throw new ProtocolException("数据长度超过 " + MAX_DATA_LENGTH + ": " + length);
        }
        byte[] out = new byte[length + 4];
        out[0] = (byte) START;
        out[1] = (byte) length;
        out[2] = (byte) (frame.sequence() & 0xFF);
        out[3] = (byte) ((frame.sequence() >>> 8) & 0xFF);
        out[4] = (byte) encryption;
        out[5] = (byte) frame.frameType();
        System.arraycopy(body, 0, out, 6, body.length);
        int crc = Crc16.compute(out, 2, length);
        out[out.length - 2] = (byte) (crc & 0xFF);
        out[out.length - 1] = (byte) ((crc >>> 8) & 0xFF);
        return out;
    }

    public static String hex(byte[] data) {
        return HEX.formatHex(data);
    }

    public static byte[] parseHex(String hex) {
        if (hex == null) {
            throw new ProtocolException("hex 不能为空");
        }
        String cleaned = hex.replaceAll("[\\s\\r\\n]", "");
        if (cleaned.length() % 2 != 0 || !cleaned.matches("(?i)[0-9a-f]*")) {
            throw new ProtocolException("非法十六进制字符串");
        }
        return HexFormat.of().parseHex(cleaned);
    }
}
