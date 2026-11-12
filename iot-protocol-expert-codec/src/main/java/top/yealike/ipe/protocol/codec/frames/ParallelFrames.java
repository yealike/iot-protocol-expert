package top.yealike.ipe.protocol.codec.frames;

import java.math.BigDecimal;
import top.yealike.ipe.protocol.codec.BinaryReader;
import top.yealike.ipe.protocol.codec.BinaryWriter;
import top.yealike.ipe.protocol.codec.Numbers;

/** 并充模式帧（0xA1~0xA4）。 */
public final class ParallelFrames {

    private ParallelFrames() {}

    public record ParallelStartRequest(
            String pileCode,
            String gunNo,
            int startType,
            boolean needPassword,
            String account,
            String passwordHex,
            String vin,
            int mainGun,
            String parallelNo) {}

    public static byte[] encodeParallelStartRequest(ParallelStartRequest f) {
        return new BinaryWriter()
                .bcd(f.pileCode(), 7)
                .bcd(f.gunNo(), 1)
                .u8(f.startType())
                .u8(f.needPassword() ? 0x01 : 0x00)
                .bytes(OperationFrames.padded(f.account(), 8))
                .bytes(OperationFrames.padded(f.passwordHex(), 16))
                .ascii(f.vin(), 17)
                .u8(f.mainGun())
                .bcd(f.parallelNo(), 6)
                .toBytes();
    }

    public static ParallelStartRequest decodeParallelStartRequest(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0xA1 主动申请并充");
        String pileCode = r.bcd(7);
        String gunNo = r.bcd(1);
        int startType = r.u8();
        boolean needPwd = r.u8() == 0x01;
        String account = OperationFrames.hex(r.bytes(8));
        String password = OperationFrames.hex(r.bytes(16));
        String vin = r.ascii(17);
        int mainGun = r.u8();
        String parallelNo = r.bcd(6);
        return new ParallelStartRequest(
                pileCode, gunNo, startType, needPwd, account, password, vin, mainGun, parallelNo);
    }

    public record ParallelStartAck(
            String transactionNo,
            String pileCode,
            String gunNo,
            String logicalCard,
            BigDecimal balance,
            boolean success,
            int failReason,
            String parallelNo) {}

    public static byte[] encodeParallelStartAck(ParallelStartAck f) {
        return new BinaryWriter()
                .bcd(f.transactionNo(), 16)
                .bcd(f.pileCode(), 7)
                .bcd(f.gunNo(), 1)
                .bcd(f.logicalCard(), 8)
                .u32(Numbers.raw(f.balance(), 2))
                .u8(f.success() ? 0x01 : 0x00)
                .u8(f.failReason())
                .bcd(f.parallelNo(), 6)
                .toBytes();
    }

    public static ParallelStartAck decodeParallelStartAck(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0xA2 并充启动确认");
        String tx = r.bcd(16);
        String pileCode = r.bcd(7);
        String gunNo = r.bcd(1);
        String card = r.bcd(8);
        BigDecimal balance = Numbers.decimal(r.u32(), 2);
        boolean success = r.u8() == 0x01;
        int failReason = r.u8();
        return new ParallelStartAck(tx, pileCode, gunNo, card, balance, success, failReason, r.bcd(6));
    }

    public record ParallelRemoteStart(
            String transactionNo,
            String pileCode,
            String gunNo,
            String logicalCard,
            String physicalCard,
            BigDecimal balance,
            String parallelNo) {}

    public static byte[] encodeParallelRemoteStart(ParallelRemoteStart f) {
        return new BinaryWriter()
                .bcd(f.transactionNo(), 16)
                .bcd(f.pileCode(), 7)
                .bcd(f.gunNo(), 1)
                .bcd(f.logicalCard(), 8)
                .bytes(OperationFrames.padded(f.physicalCard(), 8))
                .u32(Numbers.raw(f.balance(), 2))
                .bcd(f.parallelNo(), 6)
                .toBytes();
    }

    public static ParallelRemoteStart decodeParallelRemoteStart(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0xA4 远程控制并充启机");
        String tx = r.bcd(16);
        String pileCode = r.bcd(7);
        String gunNo = r.bcd(1);
        String logical = r.bcd(8);
        String physical = OperationFrames.hex(r.bytes(8));
        BigDecimal balance = Numbers.decimal(r.u32(), 2);
        return new ParallelRemoteStart(tx, pileCode, gunNo, logical, physical, balance, r.bcd(6));
    }

    public record ParallelStartResult(
            String transactionNo,
            String pileCode,
            String gunNo,
            boolean success,
            int failReason,
            int mainGun,
            String parallelNo) {}

    public static byte[] encodeParallelStartResult(ParallelStartResult f) {
        return new BinaryWriter()
                .bcd(f.transactionNo(), 16)
                .bcd(f.pileCode(), 7)
                .bcd(f.gunNo(), 1)
                .u8(f.success() ? 0x01 : 0x00)
                .u8(f.failReason())
                .u8(f.mainGun())
                .bcd(f.parallelNo(), 6)
                .toBytes();
    }

    public static ParallelStartResult decodeParallelStartResult(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0xA3 并充启机回复");
        String tx = r.bcd(16);
        String pileCode = r.bcd(7);
        String gunNo = r.bcd(1);
        boolean success = r.u8() == 0x01;
        int failReason = r.u8();
        int mainGun = r.u8();
        return new ParallelStartResult(tx, pileCode, gunNo, success, failReason, mainGun, r.bcd(6));
    }
}
