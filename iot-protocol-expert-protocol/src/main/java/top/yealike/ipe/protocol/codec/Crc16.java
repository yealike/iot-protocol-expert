package top.yealike.ipe.protocol.codec;

/**
 * CRC-16/IBM（MODBUS CRC）：多项式 0x8005（反射 0xA001），初值 0xFFFF，无最终异或。
 *
 * <p>协议正文写"多项式 0x180D"，附录 13.2 给出的码表与 MODBUS 表一致，
 * 且 0x06 样例帧可校验通过，因此以码表/样例为准。
 */
public final class Crc16 {

    private Crc16() {
    }

    public static int compute(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF);
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >>> 1) ^ 0xA001;
                } else {
                    crc >>>= 1;
                }
            }
        }
        return crc & 0xFFFF;
    }

    public static int compute(byte[] data) {
        return compute(data, 0, data.length);
    }

    public static int fromWire(byte low, byte high) {
        return ((high & 0xFF) << 8) | (low & 0xFF);
    }
}