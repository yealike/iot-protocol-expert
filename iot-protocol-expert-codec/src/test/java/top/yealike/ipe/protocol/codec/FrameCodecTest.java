package top.yealike.ipe.protocol.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import top.yealike.ipe.protocol.codec.frames.OperationFrames;
import top.yealike.ipe.protocol.codec.frames.RealtimeFrames;
import top.yealike.ipe.protocol.codec.frames.RegistrationFrames;

class FrameCodecTest {

    /** 规范 6.6 计费模型验证请求应答样例报文。 */
    private static final String RATE_VERIFY_ACK_SAMPLE = "680ECE040006550314127823050000008E2F";

    @Test
    void decodesRateVerifyAckSample() {
        Frame frame = FrameCodec.decode(FrameCodec.parseHex(RATE_VERIFY_ACK_SAMPLE), null);
        assertEquals(1230, frame.sequence());
        assertFalse(frame.encrypted());
        assertEquals(FrameType.RATE_VERIFY_ACK, frame.type());
        RegistrationFrames.RateVerifyAck ack = RegistrationFrames.decodeRateVerifyAck(frame.body());
        assertEquals("55031412782305", ack.pileCode());
        assertEquals("0000", ack.modelNo());
        assertTrue(ack.consistent());
    }

    @Test
    void crcMatchesSample() {
        byte[] data = FrameCodec.parseHex(RATE_VERIFY_ACK_SAMPLE);
        int crc = Crc16.compute(data, 2, data[1] & 0xFF);
        assertEquals(0x2F8E, crc);
    }

    @Test
    void loginFrameMatchesProtocolSample() {
        RegistrationFrames.Login login =
                new RegistrationFrames.Login("55031412782305", 0, 2, 0x0F, "v4.1.50", 0x01, "0101010101", 0x04);
        byte[] body = RegistrationFrames.encodeLogin(login);
        assertEquals(30, body.length);

        Frame frame = new Frame(0, false, FrameType.LOGIN.code(), body);
        byte[] wire = FrameCodec.encode(frame, false, null);
        String hex = FrameCodec.hex(wire);
        assertEquals("68", hex.substring(0, 2));
        assertEquals("22", hex.substring(2, 4));
        assertEquals("01", hex.substring(10, 12));
        assertEquals("55031412782305", hex.substring(12, 26));
        assertEquals(frame, FrameCodec.decode(wire, null));
    }

    @Test
    void realtimeRoundTrip() {
        RealtimeFrames.Realtime value = new RealtimeFrames.Realtime(
                "32010600019236012611091030000001",
                "32010600019236",
                "01",
                0x03,
                1,
                1,
                new BigDecimal("750.5"),
                new BigDecimal("100.0"),
                35,
                "0000000000000000",
                60,
                40,
                30,
                20,
                new BigDecimal("12.3456"),
                new BigDecimal("12.3456"),
                new BigDecimal("18.5000"),
                0,
                null);
        byte[] body = RealtimeFrames.encodeRealtime(value);
        assertEquals(60, body.length);
        RealtimeFrames.Realtime decoded = RealtimeFrames.decodeRealtime(body);
        assertEquals(value.transactionNo(), decoded.transactionNo());
        assertEquals(0, value.chargedEnergy().compareTo(decoded.chargedEnergy()));
        assertEquals(35, decoded.gunLineTemp());
        assertEquals(40, decoded.batteryMaxTemp());
    }

    @Test
    void rateModelRoundTrip() {
        RegistrationFrames.RateModel model = new RegistrationFrames.RateModel(
                "55031412782305",
                "0100",
                new BigDecimal("1.30000"),
                new BigDecimal("0.40000"),
                new BigDecimal("1.20000"),
                new BigDecimal("0.35000"),
                new BigDecimal("0.90000"),
                new BigDecimal("0.30000"),
                new BigDecimal("0.50000"),
                new BigDecimal("0.20000"),
                0,
                Collections.nCopies(48, 2));
        byte[] body = RegistrationFrames.encodeRateModel(model);
        assertEquals(90, body.length);
        RegistrationFrames.RateModel decoded = RegistrationFrames.decodeRateModel(body);
        assertEquals("55031412782305", decoded.pileCode());
        assertEquals(0, model.sharpEnergy().compareTo(decoded.sharpEnergy()));
        assertEquals(48, decoded.segments().size());
    }

    @Test
    void transactionRoundTrip() {
        OperationFrames.EnergySegment seg = new OperationFrames.EnergySegment(
                new BigDecimal("1.30000"), new BigDecimal("2.0000"),
                new BigDecimal("2.1000"), new BigDecimal("2.7300"));
        OperationFrames.Transaction tx = new OperationFrames.Transaction(
                "32010600019236012611091030000001",
                "32010600019236",
                "01",
                LocalDateTime.of(2026, 11, 9, 10, 0, 0),
                LocalDateTime.of(2026, 11, 9, 10, 30, 0),
                seg,
                seg,
                seg,
                seg,
                new BigDecimal("1000.0000"),
                new BigDecimal("1008.4000"),
                new BigDecimal("8.4000"),
                new BigDecimal("8.4000"),
                new BigDecimal("10.9200"),
                "LSVAA2A40NX000001",
                0x01,
                LocalDateTime.of(2026, 11, 9, 10, 30, 1),
                0x40,
                "00000000D14B0A54");
        byte[] body = OperationFrames.encodeTransaction(tx);
        assertEquals(158, body.length);
        OperationFrames.Transaction decoded = OperationFrames.decodeTransaction(body);
        assertEquals(tx.transactionNo(), decoded.transactionNo());
        assertEquals(0, tx.totalAmount().compareTo(decoded.totalAmount()));
        assertEquals(0x40, decoded.stopReason());
        assertEquals(tx.startTime(), decoded.startTime());
    }

    @Test
    void encryptedFrameRoundTrip() {
        byte[] key = TripleDes.keyFromHex("0123456789ABCDEFFEDCBA9876543210");
        RegistrationFrames.Login login = new RegistrationFrames.Login(
                "32010600019236", 1, 1, 0x10, "v1.0.0", 0x01, "89860000000000000000", 0x00);
        Frame frame = new Frame(7, true, FrameType.LOGIN.code(), RegistrationFrames.encodeLogin(login));
        byte[] wire = FrameCodec.encode(frame, true, key);
        assertEquals(0x01, wire[4]);
        Frame decoded = FrameCodec.decode(wire, key);
        assertTrue(decoded.encrypted());
        assertEquals(
                login.pileCode(), RegistrationFrames.decodeLogin(decoded.body()).pileCode());
    }

    @Test
    void rejectsBadCrc() {
        byte[] wire = FrameCodec.parseHex(RATE_VERIFY_ACK_SAMPLE);
        wire[wire.length - 1] ^= 0x01;
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(wire, null));
    }
}
