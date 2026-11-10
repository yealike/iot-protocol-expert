package top.yealike.ipe.protocol.codec;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;

/** 小端二进制写入器，配合协议字段定义使用。 */
public final class BinaryWriter {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    public BinaryWriter u8(int v) {
        out.write(v & 0xFF);
        return this;
    }

    public BinaryWriter u16(int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        return this;
    }

    public BinaryWriter u32(long v) {
        out.write((int) (v & 0xFF));
        out.write((int) ((v >>> 8) & 0xFF));
        out.write((int) ((v >>> 16) & 0xFF));
        out.write((int) ((v >>> 24) & 0xFF));
        return this;
    }

    /** 写入 n 字节无符号整数（n <= 8），低字节在前。 */
    public BinaryWriter uint(long v, int n) {
        for (int i = 0; i < n; i++) {
            out.write((int) ((v >>> (8 * i)) & 0xFF));
        }
        return this;
    }

    public BinaryWriter bytes(byte[] data) {
        out.writeBytes(data);
        return this;
    }

    public BinaryWriter bcd(String digits, int n) {
        out.writeBytes(Bcd.encode(digits, n));
        return this;
    }

    public BinaryWriter ascii(String s, int n) {
        byte[] raw = s == null ? new byte[0] : s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        if (raw.length > n) {
            throw new ProtocolException("ASCII 字段超出长度 " + n + ": " + s);
        }
        out.writeBytes(java.util.Arrays.copyOf(raw, n));
        return this;
    }

    public BinaryWriter cp56(LocalDateTime time) {
        out.writeBytes(Cp56Time2a.encode(time));
        return this;
    }

    public BinaryWriter zeros(int n) {
        out.writeBytes(new byte[n]);
        return this;
    }

    public int size() {
        return out.size();
    }

    public byte[] toBytes() {
        return out.toByteArray();
    }
}