package top.yealike.ipe.protocol.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProtocolCoverageTest {
    @Test
    void everyOneOf51DefinedTypesHasStructuredDecoder() {
        Map<Integer, Integer> lengths = Map.ofEntries(
                Map.entry(1, 30),
                Map.entry(2, 8),
                Map.entry(3, 9),
                Map.entry(4, 9),
                Map.entry(5, 9),
                Map.entry(6, 10),
                Map.entry(9, 7),
                Map.entry(10, 90),
                Map.entry(0x12, 8),
                Map.entry(0x13, 60),
                Map.entry(0x15, 73),
                Map.entry(0x17, 45),
                Map.entry(0x19, 39),
                Map.entry(0x1b, 32),
                Map.entry(0x1d, 28),
                Map.entry(0x21, 28),
                Map.entry(0x23, 44),
                Map.entry(0x25, 31),
                Map.entry(0x31, 51),
                Map.entry(0x32, 38),
                Map.entry(0x33, 26),
                Map.entry(0x34, 44),
                Map.entry(0x35, 10),
                Map.entry(0x36, 8),
                Map.entry(0x3b, 158),
                Map.entry(0x40, 17),
                Map.entry(0x41, 16),
                Map.entry(0x42, 20),
                Map.entry(0x43, 9),
                Map.entry(0x44, 8),
                Map.entry(0x45, 7),
                Map.entry(0x46, 8),
                Map.entry(0x47, 7),
                Map.entry(0x48, 8),
                Map.entry(0x51, 8),
                Map.entry(0x52, 9),
                Map.entry(0x55, 14),
                Map.entry(0x56, 14),
                Map.entry(0x57, 8),
                Map.entry(0x58, 90),
                Map.entry(0x61, 16),
                Map.entry(0x62, 13),
                Map.entry(0x63, 13),
                Map.entry(0x91, 8),
                Map.entry(0x92, 8),
                Map.entry(0x93, 8),
                Map.entry(0x94, 94),
                Map.entry(0xa1, 58),
                Map.entry(0xa2, 44),
                Map.entry(0xa3, 33),
                Map.entry(0xa4, 50));
        assertEquals(51, FrameType.values().length);
        for (var type : FrameType.values()) {
            byte[] body = new byte[lengths.get(type.code())];
            if (type == FrameType.TIME_SYNC || type == FrameType.TIME_SYNC_ACK) time(body, 7);
            if (type == FrameType.TRANSACTION) {
                time(body, 24);
                time(body, 31);
                time(body, 142);
            }
            assertNotNull(FramePayloads.decode(type, body), type.name());
            byte[] key = TripleDes.keyFromHex("0123456789ABCDEFFEDCBA9876543210");
            for (boolean encrypted : List.of(false, true)) {
                Frame f = FrameCodec.decode(
                        FrameCodec.encode(new Frame(65535, encrypted, type.code(), body), encrypted, key), key);
                assertNotNull(FramePayloads.decode(type, f.body()), type.name());
            }
        }
    }

    private void time(byte[] b, int offset) {
        System.arraycopy(Cp56Time2a.encode(LocalDateTime.of(2026, 11, 9, 10, 0)), 0, b, offset, 7);
    }

    @Test
    void enforcesDocument200ByteDataLimit() {
        assertThrows(
                ProtocolException.class,
                () -> FrameCodec.encode(new Frame(1, false, 0x13, new byte[197]), false, null));
    }
}
