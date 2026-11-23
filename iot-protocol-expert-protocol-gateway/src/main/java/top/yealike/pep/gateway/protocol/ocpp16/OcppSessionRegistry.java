package top.yealike.pep.gateway.protocol.ocpp16;

import io.micrometer.tracing.Tracer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import top.yealike.pep.codec.ocpp16.OcppMessage;
import top.yealike.pep.codec.ocpp16.OcppMessageCodec;
import top.yealike.pep.gateway.device.OnlineDeviceDirectory;
import top.yealike.pep.gateway.integration.ops.OpsClient;
import top.yealike.pep.gateway.integration.ops.dto.FrameRequest;

/** OCPP WebSocket 会话、CALL 下行与 CALLRESULT/CALLERROR 关联。 */
@Component
public class OcppSessionRegistry implements OnlineDeviceDirectory {

    private static final Logger log = LoggerFactory.getLogger(OcppSessionRegistry.class);

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<ChannelId, String> chargePointByChannel = new ConcurrentHashMap<>();
    private final Map<ChannelId, String> externalChargePointByChannel = new ConcurrentHashMap<>();
    private final Map<String, PendingCall> pendings = new ConcurrentHashMap<>();
    private final AtomicLong uniqueIds = new AtomicLong(System.currentTimeMillis() % 100_000);
    private final OpsClient ops;
    private final Tracer tracer;

    public OcppSessionRegistry(OpsClient ops, Tracer tracer) {
        this.ops = ops;
        this.tracer = tracer;
    }

    public void bind(String chargePointId, Channel channel) {
        bind(chargePointId, chargePointId, channel);
    }

    public void bind(String deviceSn, String externalChargePointId, Channel channel) {
        Session previous = sessions.put(deviceSn, new Session(channel, deviceSn, externalChargePointId));
        chargePointByChannel.put(channel.id(), deviceSn);
        externalChargePointByChannel.put(channel.id(), externalChargePointId);
        if (previous != null
                && previous.channel() != channel
                && previous.channel().isActive()) {
            log.info(
                    "OCPP 设备 {} 重复连接，关闭旧通道 {}",
                    deviceSn,
                    previous.channel().id().asShortText());
            previous.channel().close();
        }
        log.info(
                "OCPP 设备 {}（chargePointId {}）已绑定通道 {}",
                deviceSn,
                externalChargePointId,
                channel.id().asShortText());
    }

    public String unbind(Channel channel) {
        String deviceSn = chargePointByChannel.remove(channel.id());
        externalChargePointByChannel.remove(channel.id());
        if (deviceSn == null) {
            return null;
        }
        Session current = sessions.get(deviceSn);
        if (current == null || current.channel() != channel) {
            return null;
        }
        sessions.remove(deviceSn, current);
        pendings.forEach((id, pending) -> {
            if (pending.chargePointId().equals(deviceSn)) {
                pending.future().completeExceptionally(new IllegalStateException("设备连接断开"));
            }
        });
        log.info("OCPP 设备 {} 会话断开", deviceSn);
        return deviceSn;
    }

    public String chargePointOf(Channel channel) {
        return chargePointByChannel.get(channel.id());
    }

    public String externalChargePointOf(Channel channel) {
        return externalChargePointByChannel.get(channel.id());
    }

    public Channel channelOf(String chargePointId) {
        Session session = sessions.get(chargePointId);
        return session == null ? null : session.channel();
    }

    @Override
    public boolean isOnline(String pileCode) {
        Session session = sessions.get(pileCode);
        return session != null && session.channel().isActive();
    }

    @Override
    public Set<String> onlinePiles() {
        return Set.copyOf(sessions.keySet());
    }

    /** 平台下发 CALL，并等待对端 CALLRESULT / CALLERROR。 */
    public JsonNode call(String chargePointId, String action, Object payload, Duration timeout) {
        Session session = sessions.get(chargePointId);
        if (session == null || !session.channel().isActive()) {
            throw new IllegalStateException("OCPP 桩不在线: " + chargePointId);
        }
        String uniqueId = nextUniqueId();
        JsonNode payloadNode = OcppMessageCodec.value(payload);
        OcppMessage message = OcppMessage.call(uniqueId, action, payloadNode);
        PendingCall pending = new PendingCall(chargePointId, action, new CompletableFuture<>());
        pendings.put(uniqueId, pending);
        String json = OcppMessageCodec.encode(message);
        logFrame(session.externalChargePointId(), "OUT", message, payloadNode.toString(), json, action + " 下行");
        try {
            session.channel().writeAndFlush(new TextWebSocketFrame(json)).addListener(write -> {
                if (!write.isSuccess()) {
                    pending.future()
                            .completeExceptionally(
                                    write.cause() == null
                                            ? new IllegalStateException("write cancelled")
                                            : write.cause());
                }
            });
            OcppMessage reply = pending.future().get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (reply.isError()) {
                throw new OcppCommandException(action
                        + " 返回 "
                        + reply.errorCode()
                        + ": "
                        + (reply.errorDescription() == null ? "" : reply.errorDescription()));
            }
            return reply.payload();
        } catch (TimeoutException e) {
            throw new OcppCommandException("等待 " + action + " 应答超时（" + chargePointId + "）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcppCommandException("等待 " + action + " 应答被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new OcppCommandException(
                    "等待 " + action + " 应答失败: " + (cause == null ? e.getMessage() : cause.getMessage()), cause);
        } finally {
            pendings.remove(uniqueId, pending);
        }
    }

    /** 收到 CALLRESULT / CALLERROR 时唤醒等待者。 */
    public boolean complete(OcppMessage reply) {
        if (reply.uniqueId() == null) {
            return false;
        }
        PendingCall pending = pendings.remove(reply.uniqueId());
        return pending != null && pending.future().complete(reply);
    }

    public void sendResult(Channel channel, String uniqueId, String action, JsonNode payload) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        OcppMessage message = OcppMessage.result(uniqueId, payload == null ? OcppMessageCodec.object() : payload);
        logFrame(
                externalChargePointOf(channel),
                "OUT",
                message,
                message.payload() == null ? "" : message.payload().toString(),
                OcppMessageCodec.encode(message),
                action + " 应答");
        channel.writeAndFlush(new TextWebSocketFrame(OcppMessageCodec.encode(message)));
    }

    public void sendError(Channel channel, String uniqueId, String action, String errorCode, String description) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        OcppMessage message = OcppMessage.error(uniqueId, errorCode, description, OcppMessageCodec.object());
        logFrame(
                externalChargePointOf(channel),
                "OUT",
                message,
                description,
                OcppMessageCodec.encode(message),
                action + " 异常");
        channel.writeAndFlush(new TextWebSocketFrame(OcppMessageCodec.encode(message)));
    }

    public void logInbound(String chargePointId, OcppMessage message) {
        logFrame(
                chargePointId,
                "IN",
                message,
                message.payload() == null ? "" : message.payload().toString(),
                null,
                (message.action() == null ? message.typeLabel() : message.action()) + " 上行");
    }

    private void logFrame(
            String chargePointId, String direction, OcppMessage message, String body, String wire, String summary) {
        if (chargePointId == null) {
            return;
        }
        ops.frame(new FrameRequest(
                "OCPP16J",
                chargePointId,
                direction,
                message.messageTypeId(),
                message.action(),
                message.uniqueId(),
                0,
                false,
                body,
                wire,
                summary,
                message.errorCode(),
                traceId(),
                LocalDateTime.now()));
    }

    private String traceId() {
        var span = tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }

    private String nextUniqueId() {
        return "ipe-" + Long.toString(uniqueIds.incrementAndGet(), 36);
    }

    private record PendingCall(String chargePointId, String action, CompletableFuture<OcppMessage> future) {}
}
