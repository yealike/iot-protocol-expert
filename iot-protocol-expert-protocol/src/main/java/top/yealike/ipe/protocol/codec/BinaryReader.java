package top.yealike.ipe.protocol.codec;

import java.time.LocalDateTime;
import java.util.Arrays;

/** 小端二进制读取器，字段越界时立即报错，不做静默补齐。 */
public final class BinaryReader {

    private final byte[] data;
    private final String context;
    private int pos;

    public BinaryReader(byte[] data) {
        this(data, "消息体");
    }

    public BinaryReader(byte[] data, String context) {
        this.data = data == null ? new byte[0] : data;
        this.context = context;
    }

    public int position() {
        return pos;
    }

    public int remaining() {
        return data.length - pos;
    }

    public boolean hasRemaining() {
        return pos < data.length;
    }

    private void require(int n) {
        if (pos + n > data.length) {
            throw new ProtocolException(context + " 长度不足：需要 " + (pos + n) + " 字节，实际 " + data.length);
        }
    }

    public int u8() {
        require(1);
        return data[pos++] & 0xFF;
    }

    public int i8() {
        require(1);
        return data[pos++];
    }

    public int u16() {
        require(2);
        int v = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8);
        pos += 2;
        return v;
    }

    public long u32() {
        require(4);
        long v = ((long) data[pos] & 0xFF)
                | (((long) data[pos + 1] & 0xFF) << 8)
                | (((long) data[pos + 2] & 0xFF) << 16)
                | (((long) data[pos + 3] & 0xFF) << 24);
        pos += 4;
        return v;
    }

    public long uint(int n) {
        require(n);
        long v = 0;
        for (int i = 0; i < n; i++) {
            v |= ((long) data[pos + i] & 0xFF) << (8 * i);
        }
        pos += n;
        return v;
    }

    public byte[] bytes(int n) {
        require(n);
        byte[] out = Arrays.copyOfRange(data, pos, pos + n);
        pos += n;
        return out;
    }

    public String bcd(int n) {
        require(n);
        String s = Bcd.decode(data, pos, n);
        pos += n;
        return s;
    }

    public String ascii(int n) {
        require(n);
        String s = new String(data, pos, n, java.nio.charset.StandardCharsets.ISO_8859_1);
        pos += n;
        return s.replace('\0', ' ').trim();
    }

    public LocalDateTime cp56() {
        require(7);
        LocalDateTime t = Cp56Time2a.decode(data, pos);
        pos += 7;
        return t;
    }

    public void skip(int n) {
        require(n);
        pos += n;
    }
}