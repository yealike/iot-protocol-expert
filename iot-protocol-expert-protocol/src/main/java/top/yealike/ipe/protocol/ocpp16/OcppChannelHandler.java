package top.yealike.ipe.protocol.ocpp16;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateEvent;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import top.yealike.ipe.protocol.ocpp16.codec.OcppConstants;
import top.yealike.ipe.protocol.ocpp16.codec.OcppException;
import top.yealike.ipe.protocol.ocpp16.codec.OcppMessage;
import top.yealike.ipe.protocol.ocpp16.codec.OcppMessageCodec;
import top.yealike.ipe.protocol.ops.OpsClient;

/** OCPP WebSocket 连接入口。握手完成后绑定桩号，文本帧进入分发器。 */
public class OcppChannelHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger log = LoggerFactory.getLogger(OcppChannelHandler.class);

    private final String path;
    private final OcppSessionRegistry sessions;
    private final OcppDispatcher dispatcher;
    private final OcppTelemetry telemetry;
    private final OpsClient ops;

    private String chargePointId;

    public OcppChannelHandler(
            String path,
            OcppSessionRegistry sessions,
            OcppDispatcher dispatcher,
            OcppTelemetry telemetry,
            OpsClient ops) {
        this.path = path;
        this.sessions = sessions;
        this.dispatcher = dispatcher;
        this.telemetry = telemetry;
        this.ops = ops;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete handshake) {
            String uri = handshake.requestUri();
            chargePointId = parseChargePointId(uri);
            if (chargePointId == null) {
                log.warn("OCPP 握手路径非法 {}，关闭连接 {}", uri, ctx.channel().remoteAddress());
                ctx.close();
                return;
            }
            sessions.bind(chargePointId, ctx.channel());
            log.info(
                    "OCPP 桩 {} 已连接，协议版本 {}，来自 {}",
                    chargePointId,
                    handshake.selectedSubprotocol(),
                    ctx.channel().remoteAddress());
            return;
        }
        if (evt instanceof IdleStateEvent) {
            log.info("OCPP 桩 {} 读空闲超时，关闭连接", chargePointId);
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof PingWebSocketFrame ping) {
            ctx.channel().writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
            return;
        }
        if (frame instanceof CloseWebSocketFrame close) {
            ctx.channel()
                    .writeAndFlush(new CloseWebSocketFrame(close.statusCode(), close.reasonText()))
                    .addListener(ignored -> ctx.close());
            return;
        }
        if (frame instanceof BinaryWebSocketFrame) {
            log.debug("OCPP 桩 {} 发送二进制帧，忽略", chargePointId);
            return;
        }
        if (!(frame instanceof TextWebSocketFrame textFrame)) {
            return;
        }
        handleText(ctx, textFrame.text());
    }

    private void handleText(ChannelHandlerContext ctx, String text) {
        String pileCode = sessions.chargePointOf(ctx.channel());
        try (var operation = telemetry.start("platform", "receive", null, null, null, null)) {
            OcppMessage message;
            try {
                message = OcppMessageCodec.decode(text);
            } catch (OcppException e) {
                operation.fail(e);
                sessions.logInbound(
                        pileCode, OcppMessage.error("-1", e.errorCode(), e.getMessage(), OcppMessageCodec.object()));
                sessions.sendError(ctx.channel(), "-1", "Unknown", e.errorCode(), e.getMessage());
                return;
            }
            if (pileCode != null) {
                sessions.logInbound(pileCode, message);
            }
            if (!message.isCall()) {
                sessions.complete(message);
                return;
            }
            dispatchCall(ctx, pileCode, message);
        }
    }

    private void dispatchCall(ChannelHandlerContext ctx, String pileCode, OcppMessage message) {
        if (pileCode == null) {
            sessions.sendError(
                    ctx.channel(), message.uniqueId(), message.action(), OcppConstants.ERROR_SECURITY, "连接未绑定桩号");
            ctx.close();
            return;
        }
        try {
            JsonNode response =
                    dispatcher.handle(pileCode, message, ctx.channel().id().asLongText());
            sessions.sendResult(ctx.channel(), message.uniqueId(), message.action(), response);
        } catch (OcppException e) {
            sessions.sendError(ctx.channel(), message.uniqueId(), message.action(), e.errorCode(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("处理 OCPP 桩 {} 动作 {} 异常", pileCode, message.action(), e);
            sessions.sendError(
                    ctx.channel(),
                    message.uniqueId(),
                    message.action(),
                    OcppConstants.ERROR_INTERNAL,
                    e.getMessage() == null ? "内部错误" : e.getMessage());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        String pileCode = sessions.unbind(ctx.channel());
        if (pileCode != null) {
            try {
                ops.offline("OCPP16J", pileCode, ctx.channel().id().asLongText());
            } catch (RuntimeException e) {
                log.warn("OCPP 桩 {} 离线状态同步失败: {}", pileCode, e.getMessage());
            }
            log.info("OCPP 桩 {} 连接关闭", pileCode);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("OCPP 连接 {} 异常: {}", chargePointId, cause.getMessage());
        ctx.close();
    }

    private String parseChargePointId(String uri) {
        if (uri == null) {
            return null;
        }
        String value = uri;
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        String prefix = path + "/";
        if (!value.startsWith(prefix) || value.length() == prefix.length()) {
            return null;
        }
        String id = URLDecoder.decode(value.substring(prefix.length()), StandardCharsets.UTF_8);
        return id.isBlank() || id.contains("/") ? null : id;
    }
}
