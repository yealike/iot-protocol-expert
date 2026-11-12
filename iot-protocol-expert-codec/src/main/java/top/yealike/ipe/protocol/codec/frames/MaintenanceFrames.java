package top.yealike.ipe.protocol.codec.frames;

import top.yealike.ipe.protocol.codec.BinaryReader;
import top.yealike.ipe.protocol.codec.BinaryWriter;

/** 远程维护与地锁帧（0x61~0x63、0x91~0x94）。 */
public final class MaintenanceFrames {

    private MaintenanceFrames() {}

    // ---------------------------------------------------------------- 0x92 / 0x91 远程重启

    public record Reboot(String pileCode, int control) {}

    public static byte[] encodeReboot(Reboot f) {
        return new BinaryWriter().bcd(f.pileCode(), 7).u8(f.control()).toBytes();
    }

    public static Reboot decodeReboot(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x92 远程重启");
        return new Reboot(r.bcd(7), r.u8());
    }

    public static byte[] encodeRebootAck(String pileCode, boolean success) {
        return new BinaryWriter().bcd(pileCode, 7).u8(success ? 0x01 : 0x00).toBytes();
    }

    public record RebootAck(String pileCode, boolean success) {}

    public static RebootAck decodeRebootAck(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x91 远程重启应答");
        return new RebootAck(r.bcd(7), r.u8() == 0x01);
    }

    // ---------------------------------------------------------------- 0x94 / 0x93 远程更新

    public record Update(
            String pileCode,
            int pileModel,
            int power,
            String serverAddress,
            int port,
            String username,
            String password,
            String filePath,
            int control,
            int downloadTimeoutMinutes) {}

    public static byte[] encodeUpdate(Update f) {
        return new BinaryWriter()
                .bcd(f.pileCode(), 7)
                .u8(f.pileModel())
                .u16(f.power())
                .ascii(f.serverAddress(), 16)
                .u16(f.port())
                .ascii(f.username(), 16)
                .ascii(f.password(), 16)
                .ascii(f.filePath(), 32)
                .u8(f.control())
                .u8(f.downloadTimeoutMinutes())
                .toBytes();
    }

    public static Update decodeUpdate(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x94 远程更新");
        return new Update(
                r.bcd(7), r.u8(), r.u16(), r.ascii(16), r.u16(), r.ascii(16), r.ascii(16), r.ascii(32), r.u8(), r.u8());
    }

    public static byte[] encodeUpdateAck(String pileCode, int status) {
        return new BinaryWriter().bcd(pileCode, 7).u8(status).toBytes();
    }

    public record UpdateAck(String pileCode, int status) {}

    public static UpdateAck decodeUpdateAck(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x93 远程更新应答");
        return new UpdateAck(r.bcd(7), r.u8());
    }

    // ---------------------------------------------------------------- 0x61 地锁上报

    public record LockReport(
            String pileCode,
            int gunNo,
            int lockStatus,
            int parkingStatus,
            int battery,
            int alarm,
            String reservedHex) {}

    public static byte[] encodeLockReport(LockReport f) {
        return new BinaryWriter()
                .bcd(f.pileCode(), 7)
                .u8(f.gunNo())
                .u8(f.lockStatus())
                .u8(f.parkingStatus())
                .u8(f.battery())
                .u8(f.alarm())
                .zeros(4)
                .toBytes();
    }

    public static LockReport decodeLockReport(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x61 地锁数据上送");
        return new LockReport(
                r.bcd(7),
                r.u8(),
                r.u8(),
                r.u8(),
                r.u8(),
                r.u8(),
                java.util.HexFormat.of().withUpperCase().formatHex(r.bytes(4)));
    }

    // ---------------------------------------------------------------- 0x62 / 0x63 地锁控制

    public record LockControl(String pileCode, int gunNo, int action) {}

    public static byte[] encodeLockControl(LockControl f) {
        return new BinaryWriter()
                .bcd(f.pileCode(), 7)
                .u8(f.gunNo())
                .u8(f.action())
                .zeros(4)
                .toBytes();
    }

    public static LockControl decodeLockControl(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x62 遥控地锁");
        return new LockControl(r.bcd(7), r.u8(), r.u8());
    }

    public record LockControlAck(String pileCode, int gunNo, boolean success) {}

    public static byte[] encodeLockControlAck(LockControlAck f) {
        return new BinaryWriter()
                .bcd(f.pileCode(), 7)
                .u8(f.gunNo())
                .u8(f.success() ? 0x01 : 0x00)
                .zeros(4)
                .toBytes();
    }

    public static LockControlAck decodeLockControlAck(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x63 地锁控制返回");
        return new LockControlAck(r.bcd(7), r.u8(), r.u8() == 0x01);
    }
}
