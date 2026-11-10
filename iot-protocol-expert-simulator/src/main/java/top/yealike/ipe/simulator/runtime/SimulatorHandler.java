package top.yealike.ipe.simulator.runtime;

import top.yealike.ipe.protocol.codec.InboundFrame;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 模拟器 TCP 通道处理器。 */
public class SimulatorHandler extends SimpleChannelInboundHandler<InboundFrame> {

    private static final Logger log = LoggerFactory.getLogger(SimulatorHandler.class);

    private final SimulatorRuntime runtime;

    public SimulatorHandler(SimulatorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        runtime.onConnected(ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        runtime.onDisconnected(ctx.channel());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, InboundFrame inbound) {
        runtime.onFrame(inbound.frame());
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            log.info("模拟器读空闲超时，主动断开");
            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        runtime.onError(cause);
        ctx.close();
    }
}