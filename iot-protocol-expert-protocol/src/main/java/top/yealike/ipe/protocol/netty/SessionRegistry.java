package top.yealike.ipe.protocol.netty;

import top.yealike.ipe.protocol.codec.Frame;
import top.yealike.ipe.protocol.codec.FrameCodec;
import top.yealike.ipe.protocol.codec.FrameType;
import top.yealike.ipe.protocol.codec.ProtocolException;
import top.yealike.ipe.protocol.codec.TripleDes;
import top.yealike.ipe.protocol.config.ProtocolProperties;
import top.yealike.ipe.protocol.service.FrameLogService;
import top.yealike.ipe.protocol.telemetry.ProtocolTelemetry;
import io.micrometer.tracing.TraceContext;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** 在线会话、下行发送与请求-应答关联。 */
@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<ChannelId, String> pileByChannel = new ConcurrentHashMap<>();
    private final Map<String, Pending> pendings = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    private final ProtocolTelemetry telemetry;
    private final FrameLogService frameLogService;
    private final ProtocolProperties properties;
    private final byte[] cryptoKey;

    public SessionRegistry(FrameLogService frameLogService, ProtocolProperties properties, ProtocolTelemetry telemetry) {
        this.telemetry = telemetry;
        this.frameLogService = frameLogService;
        this.properties = properties;
        this.cryptoKey = TripleDes.keyFromHex(properties.getCryptoKeyHex());
    }

    public record Session(Channel channel, String pileCode, boolean encrypted, AtomicInteger sequence) {
        public Session(Channel channel,String pileCode,boolean encrypted) { this(channel,pileCode,encrypted,new AtomicInteger()); }
        public int next() { return sequence.getAndUpdate(v -> v>=65535?0:v+1); }
    }

    public void bind(String pileCode, Channel channel, boolean encrypted) {
        Session previous = sessions.put(pileCode, new Session(channel, pileCode, encrypted));
        pileByChannel.put(channel.id(), pileCode);
        if (previous != null && previous.channel() != channel) {
            previous.channel().close();
        }
        log.info("桩 {} 已绑定会话 {}，加密={}", pileCode, channel.id().asShortText(), encrypted);
    }

    public String unbind(Channel channel) {
        String pileCode = pileByChannel.remove(channel.id());
        if (pileCode != null) {
            Session current=sessions.get(pileCode);
            if(current==null || current.channel()!=channel)return null;
            if(sessions.remove(pileCode,current)) {
                pendings.forEach((k,v)->{if(k.startsWith(pileCode+":"))v.future().completeExceptionally(new IllegalStateException("设备连接断开"));});
            }
            log.info("桩 {} 会话断开", pileCode);
        }
        return pileCode;
    }

    public String pileOf(Channel channel) {
        return pileByChannel.get(channel.id());
    }

    public boolean isOnline(String pileCode) {
        Session session = sessions.get(pileCode);
        return session != null && session.channel().isActive();
    }

    public Set<String> onlinePiles() {
        return Set.copyOf(sessions.keySet());
    }

    public void setEncrypted(String pileCode, boolean encrypted) {
        Session session = sessions.get(pileCode);
        if (session != null) {
            sessions.put(pileCode, new Session(session.channel(), pileCode, encrypted, session.sequence()));
        }
    }

    /** 发送一帧，不等待应答。 */
    public void send(String pileCode, FrameType type, byte[] body) { send(pileCode,type,body,null); }
    public void send(String pileCode, FrameType type, byte[] body, Integer responseSequence) {
        Session session=sessions.get(pileCode);
        if(session==null||!session.channel().isActive())throw new top.yealike.ipe.protocol.service.DeviceService.DeviceOfflineException("桩不在线: "+pileCode);
        Frame frame=new Frame(responseSequence==null?session.next():responseSequence,session.encrypted(),type.code(),body);
        try (var operation = telemetry.start("platform", "send", frame, telemetry.capture())) {
            try {
                byte[] wire=FrameCodec.encode(frame,session.encrypted(),cryptoKey);
                frameLogService.outbound(frame,pileCode,FrameCodec.hex(wire));
                operation.completeOn(session.channel().writeAndFlush(Unpooled.wrappedBuffer(wire)));
            } catch (RuntimeException e) {
                operation.fail(e);
                throw e;
            }
        }
    }

    /** 发送并等待指定应答帧，超时抛 CommandTimeoutException。 */
    public Frame request(String pileCode, FrameType requestType, FrameType ackType, byte[] body, Duration timeout) {
        var marker = new Frame(0, false, requestType.code(), null);
        try (var operation = telemetry.start("platform", "request", marker, telemetry.capture())) {
            try {
                return doRequest(pileCode, requestType, ackType, body, timeout, operation);
            } catch (RuntimeException e) {
                operation.fail(e);
                throw e;
            }
        }
    }

    private Frame doRequest(String pileCode, FrameType requestType, FrameType ackType, byte[] body,
                            Duration timeout, ProtocolTelemetry.Operation operation) {
        Session session = sessions.get(pileCode);
        if (session == null || !session.channel().isActive()) {
            throw new top.yealike.ipe.protocol.service.DeviceService.DeviceOfflineException("桩不在线: " + pileCode);
        }
        int seq = session.next();
        Frame frame = new Frame(seq, session.encrypted(), requestType.code(), body);
        operation.frame(frame);
        String key = pendingKey(pileCode, ackType,seq);
        Pending pending = new Pending(key, new CompletableFuture<>(), telemetry.capture());
        if(pendings.putIfAbsent(key,pending)!=null)throw new IllegalStateException("序列号仍有待处理命令");

        try {
            byte[] wire = FrameCodec.encode(frame, session.encrypted(), cryptoKey);
            frameLogService.outbound(frame, pileCode, FrameCodec.hex(wire));
            session.channel().writeAndFlush(Unpooled.wrappedBuffer(wire)).addListener(write -> {
                if (!write.isSuccess()) pending.future().completeExceptionally(
                        write.cause() == null ? new IllegalStateException("write cancelled") : write.cause());
            });
            return pending.future().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            removePending(key, pending);
            throw new CommandTimeoutException("等待 " + ackType.label() + " 超时（" + pileCode + "）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            removePending(key, pending);
            throw new CommandTimeoutException("等待应答被中断", e);
        } catch (ExecutionException e) {
            removePending(key, pending);
            throw new CommandTimeoutException("等待应答失败: " + e.getCause().getMessage(), e.getCause());
        } finally {
            removePending(key, pending);
        }
    }

    /** 收到应答帧时唤醒等待者。 */
    public boolean complete(String pileCode,Frame frame) {
        FrameType type=FrameType.ofOrNull(frame.frameType());if(type==null)return false;
        Pending pending=pendings.remove(pendingKey(pileCode,type,frame.sequence()));
        return pending!=null && pending.future().complete(frame);
    }
    private void removePending(String key,Pending pending) {pendings.remove(key,pending);}
    private static String pendingKey(String pileCode,FrameType type,int sequence) {return pileCode+":"+type.code()+":"+sequence;}
    /** Correlate a local ACK with its waiting command without modifying the TCP wire. */
    public TraceContext pendingContext(String pileCode, Frame frame) {
        FrameType type = FrameType.ofOrNull(frame.frameType());
        if (pileCode == null || type == null) return null;
        Pending pending = pendings.get(pendingKey(pileCode, type, frame.sequence()));
        return pending == null ? null : pending.context();
    }
    private record Pending(String key,CompletableFuture<Frame> future,TraceContext context) {}

    public static class CommandTimeoutException extends RuntimeException {
        private final boolean timeout;
        public CommandTimeoutException(String message) {
            super(message);
            timeout = true;
        }
        public CommandTimeoutException(String message, Throwable cause) {
            super(message, cause);
            timeout = cause instanceof TimeoutException;
        }
        public boolean isTimeout() { return timeout; }
    }

    public static byte[] keyOf(ProtocolProperties properties) {
        try {
            return TripleDes.keyFromHex(properties.getCryptoKeyHex());
        } catch (ProtocolException e) {
            throw new IllegalStateException("3DES 密钥配置非法", e);
        }
    }
}
