package top.yealike.ipe.protocol.codec.frames;

import top.yealike.ipe.protocol.codec.BinaryReader;
import top.yealike.ipe.protocol.codec.BinaryWriter;

import java.time.LocalDateTime;

/** 运营平台设置帧（0x51、0x52、0x55、0x56）。 */
public final class SettingFrames {

    private SettingFrames() {
    }

    /** 0x52 工作参数设置：是否允许工作 + 最大输出功率百分比。 */
    public record ParamSet(String pileCode, boolean allowWork, int maxPowerPercent) {
    }

    public static byte[] encodeParamSet(ParamSet f) {
        return new BinaryWriter().bcd(f.pileCode(), 7)
                .u8(f.allowWork() ? 0x00 : 0x01)
                .u8(f.maxPowerPercent())
                .toBytes();
    }

    public static ParamSet decodeParamSet(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x52 工作参数设置");
        String pileCode = r.bcd(7);
        boolean allow = r.u8() == 0x00;
        int power = r.u8();
        return new ParamSet(pileCode, allow, power);
    }

    /** 0x51 工作参数设置应答。 */
    public static byte[] encodeParamSetAck(String pileCode, boolean success) {
        return new BinaryWriter().bcd(pileCode, 7).u8(success ? 0x01 : 0x00).toBytes();
    }

    public record ParamSetAck(String pileCode, boolean success) {
    }

    public static ParamSetAck decodeParamSetAck(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x51 工作参数设置应答");
        return new ParamSetAck(r.bcd(7), r.u8() == 0x01);
    }

    /** 0x56 对时设置。 */
    public record TimeSync(String pileCode, LocalDateTime time) {
    }

    public static byte[] encodeTimeSync(TimeSync f) {
        return new BinaryWriter().bcd(f.pileCode(), 7).cp56(f.time()).toBytes();
    }

    public static TimeSync decodeTimeSync(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x56 对时设置");
        return new TimeSync(r.bcd(7), r.cp56());
    }

    /** 0x55 对时应答。 */
    public static byte[] encodeTimeSyncAck(TimeSync f) {
        return new BinaryWriter().bcd(f.pileCode(), 7).cp56(f.time()).toBytes();
    }

    public static TimeSync decodeTimeSyncAck(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x55 对时设置应答");
        return new TimeSync(r.bcd(7), r.cp56());
    }

}