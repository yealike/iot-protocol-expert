package top.yealike.ipe.protocol.codec;

import java.time.LocalDateTime;

/** CP56Time2a：2 字节毫秒（小端）+ 分 + 时 + 日 + 月 + 年（2000 起）。 */
public final class Cp56Time2a {

    private Cp56Time2a() {}

    public static byte[] encode(LocalDateTime time) {
        int ms = time.getSecond() * 1000 + time.getNano() / 1_000_000;
        byte[] out = new byte[7];
        out[0] = (byte) (ms & 0xFF);
        out[1] = (byte) ((ms >>> 8) & 0xFF);
        out[2] = (byte) (time.getMinute() & 0x3F);
        out[3] = (byte) (time.getHour() & 0x1F);
        out[4] = (byte) (time.getDayOfMonth() & 0x1F);
        out[5] = (byte) (time.getMonthValue() & 0x0F);
        out[6] = (byte) ((time.getYear() - 2000) & 0x7F);
        return out;
    }

    public static LocalDateTime decode(byte[] data, int offset) {
        int ms = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
        int minute = data[offset + 2] & 0x3F;
        int hour = data[offset + 3] & 0x1F;
        int day = data[offset + 4] & 0x1F;
        int month = data[offset + 5] & 0x0F;
        int year = 2000 + (data[offset + 6] & 0x7F);
        int second = ms / 1000;
        int nanos = (ms % 1000) * 1_000_000;
        if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 59) {
            throw new ProtocolException(
                    "CP56Time2a 字段越界: " + year + "-" + month + "-" + day + " " + hour + ":" + minute + ":" + second);
        }
        return LocalDateTime.of(year, month, day, hour, minute, second, nanos);
    }
}
