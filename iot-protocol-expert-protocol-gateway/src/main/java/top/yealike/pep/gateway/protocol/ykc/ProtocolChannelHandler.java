package top.yealike.pep.gateway.protocol.ykc;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.yealike.pep.codec.InboundFrame;
import top.yealike.pep.gateway.integration.ops.OpsClient;
import top.yealike.pep.observability.protocol.ProtocolTelemetry;

/** TCP 会话入口。未登录会话只接受 0x01；登录后交给分发器。 */
@ChannelHandler.Sharable
public class ProtocolChannelHandler extends SimpleChannelInboundHandler<InboundFrame> {

    private static final Logger log = LoggerFactory.getLogger(ProtocolChannelHandler.class);

    private final ProtocolTelemetry telemetry;
    private final SessionRegistry sessions;
    private final ProtocolDispatcher dispatcher;
    private final OpsClient ops;

    public ProtocolChannelHandler(
            SessionRegistry sessions, ProtocolDispatcher dispatcher, OpsClient ops, ProtocolTelemetry telemetry) {
        this.telemetry = telemetry;
        this.sessions = sessions;
        this.dispatcher = dispatcher;
        this.ops = ops;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, InboundFrame inbound) {
        String deviceSn = sessions.pileOf(ctx.channel());
        String externalPileCode = sessions.externalPileOf(ctx.channel());
        try (var operation = telemetry.start(
                "platform", "receive", inbound.frame(), sessions.pendingContext(deviceSn, inbound.frame()))) {
            try {
                handleFrame(ctx, inbound, deviceSn, externalPileCode);
            } catch (RuntimeException e) {
                operation.fail(e);
                throw e;
            }
        }
    }

    private void handleFrame(
            ChannelHandlerContext ctx, InboundFrame inbound, String deviceSn, String externalPileCode) {
        if (deviceSn == null) {
            if (inbound.frame().frameType() != 0x01) {
                telemetry.failure(new IllegalStateException("unauthenticated frame"));
                log.warn(
                        "未登录连接 {} 发送帧 0x{}，关闭连接",
                        ctx.channel().remoteAddress(),
                        String.format("%02X", inbound.frame().frameType()));
                ctx.close();
                return;
            }
            dispatcher.onLogin(ctx.channel(), inbound);
            return;
        }
        dispatcher.dispatch(deviceSn, externalPileCode, inbound);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        String deviceSn = sessions.unbind(ctx.channel());
        if (deviceSn != null) {
            try {
                ops.offline("YKC16", deviceSn, ctx.channel().id().asLongText());
            } catch (RuntimeException e) {
                log.warn("设备 {} 离线状态同步失败: {}", deviceSn, e.getMessage());
            }
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            log.info("连接 {} 读空闲超时，关闭", ctx.channel().remoteAddress());
            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("连接 {} 异常", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
