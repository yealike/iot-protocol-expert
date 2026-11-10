package top.yealike.ipe.protocol.codec;

import top.yealike.ipe.protocol.codec.frames.RegistrationFrames;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class FrameStreamDecoderTest {

    @Test
    void decodesSingleFrame() {
        RegistrationFrames.Login login = new RegistrationFrames.Login(
                "32010600000001", 0, 1, 0x10, "v1.0.0", 1, "89860000000000000000", 0);
        Frame frame = new Frame(1, false, FrameType.LOGIN.code(), RegistrationFrames.encodeLogin(login));
        byte[] wire = FrameCodec.encode(frame, false, null);
        assertEquals(38, wire.length);

        EmbeddedChannel channel = new EmbeddedChannel(new FrameStreamDecoder(null));
        channel.writeInbound(Unpooled.wrappedBuffer(wire));
        Object inbound = channel.readInbound();
        assertInstanceOf(InboundFrame.class, inbound);
        assertEquals(FrameType.LOGIN, ((InboundFrame) inbound).frame().type());
        assertNull(channel.readInbound());
    }

    @Test
    void decodesStickyFrames() {
        RegistrationFrames.Heartbeat hb = new RegistrationFrames.Heartbeat("32010600000001", "01", false);
        Frame a = new Frame(1, false, FrameType.HEARTBEAT.code(), RegistrationFrames.encodeHeartbeat(hb));
        Frame b = new Frame(2, false, FrameType.HEARTBEAT.code(), RegistrationFrames.encodeHeartbeat(hb));
        byte[] wa = FrameCodec.encode(a, false, null);
        byte[] wb = FrameCodec.encode(b, false, null);
        byte[] both = new byte[wa.length + wb.length];
        System.arraycopy(wa, 0, both, 0, wa.length);
        System.arraycopy(wb, 0, both, wa.length, wb.length);

        EmbeddedChannel channel = new EmbeddedChannel(new FrameStreamDecoder(null));
        channel.writeInbound(Unpooled.wrappedBuffer(both));
        assertInstanceOf(InboundFrame.class, channel.readInbound());
        assertInstanceOf(InboundFrame.class, channel.readInbound());
    }

    @Test
    void decodesHalfPacket() {
        RegistrationFrames.Heartbeat hb = new RegistrationFrames.Heartbeat("32010600000001", "01", false);
        byte[] wire = FrameCodec.encode(new Frame(1, false, FrameType.HEARTBEAT.code(),
                RegistrationFrames.encodeHeartbeat(hb)), false, null);

        EmbeddedChannel channel = new EmbeddedChannel(new FrameStreamDecoder(null));
        channel.writeInbound(Unpooled.wrappedBuffer(wire, 0, 5));
        assertNull(channel.readInbound());
        channel.writeInbound(Unpooled.wrappedBuffer(wire, 5, wire.length - 5));
        assertInstanceOf(InboundFrame.class, channel.readInbound());
    }
}