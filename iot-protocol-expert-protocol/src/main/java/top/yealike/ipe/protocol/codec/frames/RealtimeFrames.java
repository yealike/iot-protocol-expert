package top.yealike.ipe.protocol.codec.frames;

import top.yealike.ipe.protocol.codec.BinaryReader;
import top.yealike.ipe.protocol.codec.BinaryWriter;
import top.yealike.ipe.protocol.codec.Numbers;

import java.math.BigDecimal;
import java.util.HexFormat;

/** 实时监测与充电过程帧（0x12~0x25）。 */
public final class RealtimeFrames {

    private RealtimeFrames() {
    }

    // ---------------------------------------------------------------- 0x12 读取实时监测数据

    public record ReadRealtime(String pileCode, String gunNo) {
    }

    public static byte[] encodeReadRealtime(ReadRealtime req) {
        return new BinaryWriter().bcd(req.pileCode(), 7).bcd(req.gunNo(), 1).toBytes();
    }

    public static ReadRealtime decodeReadRealtime(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x12 读取实时监测数据");
        return new ReadRealtime(r.bcd(7), r.bcd(1));
    }

    // ---------------------------------------------------------------- 0x13 上传实时监测数据

    public static final int STATE_OFFLINE = 0x00;
    public static final int STATE_FAULT = 0x01;
    public static final int STATE_IDLE = 0x02;
    public static final int STATE_CHARGING = 0x03;

    /**
     * 实时监测数据。电压/电流 0.1 倍率，电量/金额 4 位小数，温度字段偏移 -50。
     */
    public record Realtime(String transactionNo, String pileCode, String gunNo,
                           int state, int gunHomed, int plugged,
                           BigDecimal outputVoltage, BigDecimal outputCurrent,
                           int gunLineTemp, String gunLineCode, int soc, int batteryMaxTemp,
                           int totalChargeMinutes, int remainMinutes,
                           BigDecimal chargedEnergy, BigDecimal lossEnergy, BigDecimal chargedAmount,
                           int hardwareFault, String rawExtra) {
    }

    public static byte[] encodeRealtime(Realtime v) {
        BinaryWriter w = new BinaryWriter()
                .bcd(v.transactionNo(), 16)
                .bcd(v.pileCode(), 7)
                .bcd(v.gunNo(), 1)
                .u8(v.state())
                .u8(v.gunHomed())
                .u8(v.plugged())
                .u16(Numbers.rawInt(v.outputVoltage(), 1))
                .u16(Numbers.rawInt(v.outputCurrent(), 1))
                .u8(v.gunLineTemp() + 50)
                .bytes(gunLineCode(v.gunLineCode()))
                .u8(v.soc())
                .u8(v.batteryMaxTemp() + 50)
                .u16(v.totalChargeMinutes())
                .u16(v.remainMinutes())
                .u32(Numbers.raw(v.chargedEnergy(), 4))
                .u32(Numbers.raw(v.lossEnergy(), 4))
                .u32(Numbers.raw(v.chargedAmount(), 4))
                .u16(v.hardwareFault());
        if (v.rawExtra() != null && !v.rawExtra().isBlank()) {
            w.bytes(HexFormat.of().parseHex(v.rawExtra().replaceAll("\\s+", "")));
        }
        return w.toBytes();
    }

    public static Realtime decodeRealtime(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x13 上传实时监测数据");
        String tx = r.bcd(16);
        String pileCode = r.bcd(7);
        String gunNo = r.bcd(1);
        int state = r.u8();
        int gunHomed = r.u8();
        int plugged = r.u8();
        BigDecimal voltage = Numbers.decimal(r.u16(), 1);
        BigDecimal current = Numbers.decimal(r.u16(), 1);
        int gunLineTemp = r.u8() - 50;
        String gunLineCode = HexFormat.of().withUpperCase().formatHex(r.bytes(8));
        int soc = r.u8();
        int batteryMaxTemp = r.u8() - 50;
        int totalChargeMinutes = r.u16();
        int remainMinutes = r.u16();
        BigDecimal charged = Numbers.decimal(r.u32(), 4);
        BigDecimal loss = Numbers.decimal(r.u32(), 4);
        BigDecimal amount = Numbers.decimal(r.u32(), 4);
        int fault = r.u16();
        String extra = r.hasRemaining() ? HexFormat.of().withUpperCase().formatHex(r.bytes(r.remaining())) : null;
        return new Realtime(tx, pileCode, gunNo, state, gunHomed, plugged, voltage, current,
                gunLineTemp, gunLineCode, soc, batteryMaxTemp, totalChargeMinutes, remainMinutes,
                charged, loss, amount, fault, extra);
    }

    private static byte[] gunLineCode(String hex) {
        byte[] out = new byte[8];
        if (hex == null || hex.isBlank()) {
            return out;
        }
        String cleaned = hex.replaceAll("\\s+", "");
        byte[] raw = HexFormat.of().parseHex(cleaned);
        System.arraycopy(raw, 0, out, 0, Math.min(raw.length, 8));
        return out;
    }

    // ---------------------------------------------------------------- BMS 过程帧 0x15/0x17/0x19/0x1B/0x1D/0x21/0x23/0x25

    /** 公共头部 + 原始数据，保留解析与入库能力，不在此层展开国标字段。 */
    public record BmsRaw(String transactionNo, String pileCode, String gunNo, byte[] payload) {

        public BmsRaw {
            payload = payload == null ? new byte[0] : payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        public String payloadHex() {
            return HexFormat.of().withUpperCase().formatHex(payload);
        }
    }

    public static byte[] encodeBmsRaw(BmsRaw f) {
        return new BinaryWriter()
                .bcd(f.transactionNo(), 16)
                .bcd(f.pileCode(), 7)
                .bcd(f.gunNo(), 1)
                .bytes(f.payload())
                .toBytes();
    }

    public static BmsRaw decodeBmsRaw(byte[] body) {
        BinaryReader r = new BinaryReader(body, "BMS 过程帧");
        String tx = r.bcd(16);
        String pileCode = r.bcd(7);
        String gunNo = r.bcd(1);
        return new BmsRaw(tx, pileCode, gunNo, r.bytes(r.remaining()));
    }

    /** 0x19 充电结束：公共头之后的国标字段，供解析展示。 */
    public record ChargeEnd(String transactionNo, String pileCode, String gunNo,
                            int bmsSoc, BigDecimal minCellVoltage, BigDecimal maxCellVoltage,
                            int minTemp, int maxTemp, int totalChargeMinutes,
                            BigDecimal outputEnergy, long chargerNo) {
    }

    public static byte[] encodeChargeEnd(ChargeEnd f) {
        return new BinaryWriter()
                .bcd(f.transactionNo(), 16).bcd(f.pileCode(), 7).bcd(f.gunNo(), 1)
                .u8(f.bmsSoc())
                .u16(Numbers.rawInt(f.minCellVoltage(), 2))
                .u16(Numbers.rawInt(f.maxCellVoltage(), 2))
                .u8(f.minTemp() + 50)
                .u8(f.maxTemp() + 50)
                .u16(f.totalChargeMinutes())
                .u16(Numbers.rawInt(f.outputEnergy(), 1))
                .u32(f.chargerNo()-1)
                .toBytes();
    }

    public static ChargeEnd decodeChargeEnd(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x19 充电结束");
        String tx = r.bcd(16);
        String pileCode = r.bcd(7);
        String gunNo = r.bcd(1);
        int soc = r.u8();
        BigDecimal minV = Numbers.decimal(r.u16(), 2);
        BigDecimal maxV = Numbers.decimal(r.u16(), 2);
        int minT = r.u8() - 50;
        int maxT = r.u8() - 50;
        int minutes = r.u16();
        BigDecimal energy = Numbers.decimal(r.u16(), 1);
        long chargerNo = r.u32()+1;
        return new ChargeEnd(tx, pileCode, gunNo, soc, minV, maxV, minT, maxT, minutes, energy, chargerNo);
    }
}