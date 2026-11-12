package top.yealike.ipe.protocol.codec;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 协议倍率换算。BIN 小数按“乘倍率上送”，即原始值 = 实际值 * 10^scale。 */
public final class Numbers {

    private Numbers() {}

    public static BigDecimal decimal(long raw, int scale) {
        return BigDecimal.valueOf(raw).movePointLeft(scale);
    }

    public static long raw(BigDecimal value, int scale) {
        if (value == null) {
            return 0;
        }
        return value.movePointRight(scale).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 供 u8/u16 字段使用，超范围时显式报错。 */
    public static int rawInt(BigDecimal value, int scale) {
        long raw = raw(value, scale);
        if (raw < 0 || raw > 0xFFFF) {
            throw new ProtocolException("字段值超出 2 字节范围: " + value);
        }
        return (int) raw;
    }

    public static long raw(long value, int scale) {
        return BigDecimal.valueOf(value)
                .movePointRight(scale)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    public static BigDecimal scaled(long raw, int scale) {
        return decimal(raw, scale);
    }
}
