package top.yealike.ipe.protocol.codec;

/** BCD 码工具：云快充 BCD 字段定长、保留前导零。 */
public final class Bcd {

    private Bcd() {
    }

    public static byte[] encode(String digits, int byteLength) {
        if (digits == null) {
            throw new ProtocolException("BCD 值不能为空");
        }
        String d = digits.trim();
        if (!d.matches("\\d+")) {
            throw new ProtocolException("BCD 只允许数字: " + digits);
        }
        if (d.length() > byteLength * 2) {
            throw new ProtocolException("BCD 长度超限: " + digits + " > " + (byteLength * 2));
        }
        String padded = "0".repeat(byteLength * 2 - d.length()) + d;
        byte[] out = new byte[byteLength];
        for (int i = 0; i < byteLength; i++) {
            int hi = padded.charAt(i * 2) - '0';
            int lo = padded.charAt(i * 2 + 1) - '0';
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static String decode(byte[] data, int offset, int byteLength) {
        StringBuilder sb = new StringBuilder(byteLength * 2);
        for (int i = 0; i < byteLength; i++) {
            int b = data[offset + i] & 0xFF;
            sb.append((char) ('0' + (b >>> 4)));
            sb.append((char) ('0' + (b & 0x0F)));
        }
        return sb.toString();
    }

    public static boolean isValid(byte[] data, int offset, int byteLength) {
        for (int i = 0; i < byteLength; i++) {
            int b = data[offset + i] & 0xFF;
            if ((b >>> 4) > 9 || (b & 0x0F) > 9) {
                return false;
            }
        }
        return true;
    }
}