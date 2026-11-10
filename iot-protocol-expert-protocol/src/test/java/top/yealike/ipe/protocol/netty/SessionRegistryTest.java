package top.yealike.ipe.protocol.netty;
import top.yealike.ipe.protocol.codec.*;
import top.yealike.ipe.protocol.config.ProtocolProperties;
import top.yealike.ipe.protocol.service.FrameLogService;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SessionRegistryTest {
    @Test void concurrentRequestsCompleteOnlyMatchingSequence() throws Exception {
        var sessions=new SessionRegistry(mock(FrameLogService.class),new ProtocolProperties(), new top.yealike.ipe.protocol.telemetry.ProtocolTelemetry(io.micrometer.tracing.Tracer.NOOP, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));var channel=new EmbeddedChannel();sessions.bind("32010600000001",channel,false);
        try {
            var a=CompletableFuture.supplyAsync(()->sessions.request("32010600000001",FrameType.PARAM_SET,FrameType.PARAM_SET_ACK,new byte[9],Duration.ofSeconds(3)));
            Frame fa=read(channel);
            var b=CompletableFuture.supplyAsync(()->sessions.request("32010600000001",FrameType.PARAM_SET,FrameType.PARAM_SET_ACK,new byte[9],Duration.ofSeconds(3)));
            Frame fb=read(channel);assertNotEquals(fa.sequence(),fb.sequence());
            assertFalse(sessions.complete("32010600000001",new Frame(999,false,0x51,new byte[8])));
            assertTrue(sessions.complete("32010600000001",new Frame(fb.sequence(),false,0x51,new byte[8])));assertEquals(fb.sequence(),b.get(1,TimeUnit.SECONDS).sequence());assertFalse(a.isDone());
            assertTrue(sessions.complete("32010600000001",new Frame(fa.sequence(),false,0x51,new byte[8])));assertEquals(fa.sequence(),a.get(1,TimeUnit.SECONDS).sequence());
        } finally {channel.finishAndReleaseAll();}
    }
    @Test void oldConnectionCannotUnbindNewSession(){
        var s=new SessionRegistry(mock(FrameLogService.class),new ProtocolProperties(), new top.yealike.ipe.protocol.telemetry.ProtocolTelemetry(io.micrometer.tracing.Tracer.NOOP, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));var a=new EmbeddedChannel();var b=new EmbeddedChannel();s.bind("32010600000001",a,false);s.bind("32010600000001",b,false);assertNull(s.unbind(a));assertTrue(s.isOnline("32010600000001"));b.finishAndReleaseAll();a.finishAndReleaseAll();
    }
    @Test void failedSocketWriteDoesNotWaitForCommandTimeoutAndRemovesPendingContext() {
        var channel = new EmbeddedChannel(new io.netty.channel.ChannelOutboundHandlerAdapter() {
            @Override public void write(io.netty.channel.ChannelHandlerContext ctx, Object message,
                                        io.netty.channel.ChannelPromise promise) {
                io.netty.util.ReferenceCountUtil.release(message);
                promise.setFailure(new java.io.IOException("socket closed"));
            }
        });
        var sessions = new SessionRegistry(mock(FrameLogService.class), new ProtocolProperties(), new top.yealike.ipe.protocol.telemetry.ProtocolTelemetry(io.micrometer.tracing.Tracer.NOOP, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        sessions.bind("32010600000001", channel, false);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                var error = assertThrows(SessionRegistry.CommandTimeoutException.class, () -> sessions.request(
                        "32010600000001", FrameType.PARAM_SET, FrameType.PARAM_SET_ACK, new byte[9], Duration.ofSeconds(10)));
                assertFalse(error.isTimeout(), "Socket failures must not be counted as timeouts");
            });
            assertNull(sessions.pendingContext("32010600000001", new Frame(0, false, 0x51, null)));
        } finally { channel.finishAndReleaseAll(); }
    }
    private Frame read(EmbeddedChannel ch)throws Exception {
        ByteBuf b=null;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(b==null && System.nanoTime()<end){b=ch.readOutbound();if(b==null)Thread.sleep(5);}assertNotNull(b);try{byte[] data=new byte[b.readableBytes()];b.readBytes(data);return FrameCodec.decode(data,null);}finally{b.release();}
    }
}
