package top.yealike.ipe.protocol.codec.frames;

import java.math.BigDecimal;
import java.util.List;
import top.yealike.ipe.protocol.codec.BinaryReader;
import top.yealike.ipe.protocol.codec.BinaryWriter;
import top.yealike.ipe.protocol.codec.Numbers;
import top.yealike.ipe.protocol.codec.ProtocolException;

/** 注册、心跳、计费模型相关帧（0x01~0x0A、0x57、0x58）。 */
public final class RegistrationFrames {

    private RegistrationFrames() {}

    public static final int RATE_SEGMENTS = 48;

    // ---------------------------------------------------------------- 0x01 登录

    public record Login(
            String pileCode,
            int pileType,
            int gunCount,
            int protocolVersion,
            String programVersion,
            int networkType,
            String simCard,
            int operator) {}

    public static byte[] encodeLogin(Login login) {
        return new BinaryWriter()
                .bcd(login.pileCode(), 7)
                .u8(login.pileType())
                .u8(login.gunCount())
                .u8(login.protocolVersion())
                .ascii(login.programVersion(), 8)
                .u8(login.networkType())
                .bcd(login.simCard(), 10)
                .u8(login.operator())
                .toBytes();
    }

    public static Login decodeLogin(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x01 登录认证");
        return new Login(r.bcd(7), r.u8(), r.u8(), r.u8(), r.ascii(8), r.u8(), r.bcd(10), r.u8());
    }

    // ---------------------------------------------------------------- 0x02 登录应答

    public record LoginAck(String pileCode, boolean success) {}

    public static byte[] encodeLoginAck(LoginAck ack) {
        return new BinaryWriter()
                .bcd(ack.pileCode(), 7)
                .u8(ack.success() ? 0x00 : 0x01)
                .toBytes();
    }

    public static LoginAck decodeLoginAck(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x02 登录认证应答");
        return new LoginAck(r.bcd(7), r.u8() == 0x00);
    }

    // ---------------------------------------------------------------- 0x03/0x04 心跳

    public record Heartbeat(String pileCode, String gunNo, boolean fault) {}

    public static byte[] encodeHeartbeat(Heartbeat hb) {
        return new BinaryWriter()
                .bcd(hb.pileCode(), 7)
                .bcd(hb.gunNo(), 1)
                .u8(hb.fault() ? 0x01 : 0x00)
                .toBytes();
    }

    public static Heartbeat decodeHeartbeat(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x03 心跳");
        return new Heartbeat(r.bcd(7), r.bcd(1), r.u8() == 0x01);
    }

    public record HeartbeatAck(String pileCode, String gunNo) {}

    public static byte[] encodeHeartbeatAck(HeartbeatAck ack) {
        return new BinaryWriter()
                .bcd(ack.pileCode(), 7)
                .bcd(ack.gunNo(), 1)
                .u8(0)
                .toBytes();
    }

    public static HeartbeatAck decodeHeartbeatAck(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x04 心跳应答");
        return new HeartbeatAck(r.bcd(7), r.bcd(1));
    }

    // ---------------------------------------------------------------- 0x05/0x06 计费模型验证

    public record RateVerify(String pileCode, String modelNo) {}

    public static byte[] encodeRateVerify(RateVerify v) {
        return new BinaryWriter().bcd(v.pileCode(), 7).bcd(v.modelNo(), 2).toBytes();
    }

    public static RateVerify decodeRateVerify(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x05 计费模型验证");
        return new RateVerify(r.bcd(7), r.bcd(2));
    }

    public record RateVerifyAck(String pileCode, String modelNo, boolean consistent) {}

    public static byte[] encodeRateVerifyAck(RateVerifyAck ack) {
        return new BinaryWriter()
                .bcd(ack.pileCode(), 7)
                .bcd(ack.modelNo(), 2)
                .u8(ack.consistent() ? 0x00 : 0x01)
                .toBytes();
    }

    public static RateVerifyAck decodeRateVerifyAck(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x06 计费模型验证应答");
        return new RateVerifyAck(r.bcd(7), r.bcd(2), r.u8() == 0x00);
    }

    // ---------------------------------------------------------------- 0x09 计费模型请求

    public record RateRequest(String pileCode) {}

    public static byte[] encodeRateRequest(RateRequest req) {
        return new BinaryWriter().bcd(req.pileCode(), 7).toBytes();
    }

    public static RateRequest decodeRateRequest(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x09 计费模型请求");
        return new RateRequest(r.bcd(7));
    }

    // ---------------------------------------------------------------- 0x0A / 0x58 计费模型

    /** 计费模型。费率按 5 位小数上送；48 个半小时时段各一位费率号（0 尖 1 峰 2 平 3 谷）。 */
    public record RateModel(
            String pileCode,
            String modelNo,
            BigDecimal sharpEnergy,
            BigDecimal sharpService,
            BigDecimal peakEnergy,
            BigDecimal peakService,
            BigDecimal flatEnergy,
            BigDecimal flatService,
            BigDecimal valleyEnergy,
            BigDecimal valleyService,
            int lossRatio,
            List<Integer> segments) {

        public RateModel {
            if (segments == null || segments.size() != RATE_SEGMENTS) {
                throw new ProtocolException("时段费率号必须为 " + RATE_SEGMENTS + " 个");
            }
            if (modelNo == null || !modelNo.matches("\\d{4}")) throw new ProtocolException("费率编号必须为4位数字");
            if (segments.stream().anyMatch(x -> x == null || x < 0 || x > 3)) throw new ProtocolException("费率时段必须为0~3");
            if (lossRatio < 0 || lossRatio > 100) throw new ProtocolException("计损比例必须为0~100");
            for (BigDecimal price : java.util.Arrays.asList(
                    sharpEnergy,
                    sharpService,
                    peakEnergy,
                    peakService,
                    flatEnergy,
                    flatService,
                    valleyEnergy,
                    valleyService))
                if (price == null
                        || price.signum() < 0
                        || price.scale() > 5
                        || price.compareTo(new BigDecimal("42949.67295")) > 0)
                    throw new ProtocolException("费率必须非负且最多5位小数");
            segments = List.copyOf(segments);
        }

        public BigDecimal energyPrice(int segment) {
            return switch (segments.get(segment)) {
                case 0 -> sharpEnergy;
                case 1 -> peakEnergy;
                case 2 -> flatEnergy;
                default -> valleyEnergy;
            };
        }

        public BigDecimal servicePrice(int segment) {
            return switch (segments.get(segment)) {
                case 0 -> sharpService;
                case 1 -> peakService;
                case 2 -> flatService;
                default -> valleyService;
            };
        }
    }

    public static byte[] encodeRateModel(RateModel m) {
        BinaryWriter w = new BinaryWriter()
                .bcd(m.pileCode(), 7)
                .bcd(m.modelNo(), 2)
                .u32(Numbers.raw(m.sharpEnergy(), 5))
                .u32(Numbers.raw(m.sharpService(), 5))
                .u32(Numbers.raw(m.peakEnergy(), 5))
                .u32(Numbers.raw(m.peakService(), 5))
                .u32(Numbers.raw(m.flatEnergy(), 5))
                .u32(Numbers.raw(m.flatService(), 5))
                .u32(Numbers.raw(m.valleyEnergy(), 5))
                .u32(Numbers.raw(m.valleyService(), 5))
                .u8(m.lossRatio());
        for (int segment : m.segments()) {
            w.u8(segment);
        }
        return w.toBytes();
    }

    public static RateModel decodeRateModel(byte[] body) {
        BinaryReader r = new BinaryReader(body, "计费模型");
        String pileCode = r.bcd(7);
        String modelNo = r.bcd(2);
        BigDecimal sharpEnergy = Numbers.decimal(r.u32(), 5);
        BigDecimal sharpService = Numbers.decimal(r.u32(), 5);
        BigDecimal peakEnergy = Numbers.decimal(r.u32(), 5);
        BigDecimal peakService = Numbers.decimal(r.u32(), 5);
        BigDecimal flatEnergy = Numbers.decimal(r.u32(), 5);
        BigDecimal flatService = Numbers.decimal(r.u32(), 5);
        BigDecimal valleyEnergy = Numbers.decimal(r.u32(), 5);
        BigDecimal valleyService = Numbers.decimal(r.u32(), 5);
        int lossRatio = r.u8();
        java.util.List<Integer> segments = new java.util.ArrayList<>(RATE_SEGMENTS);
        for (int i = 0; i < RATE_SEGMENTS; i++) {
            segments.add(r.u8());
        }
        return new RateModel(
                pileCode,
                modelNo,
                sharpEnergy,
                sharpService,
                peakEnergy,
                peakService,
                flatEnergy,
                flatService,
                valleyEnergy,
                valleyService,
                lossRatio,
                segments);
    }

    public static byte[] encodeRateSetAck(String pileCode, boolean success) {
        return new BinaryWriter().bcd(pileCode, 7).u8(success ? 0x01 : 0x00).toBytes();
    }

    public record RateSetAck(String pileCode, boolean success) {}

    public static RateSetAck decodeRateSetAck(byte[] body, String frameLabel) {
        BinaryReader r = new BinaryReader(body, frameLabel);
        return new RateSetAck(r.bcd(7), r.u8() == 0x01);
    }
}
