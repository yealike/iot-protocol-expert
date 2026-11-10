package top.yealike.ipe.protocol.netty;

import top.yealike.ipe.protocol.codec.FrameStreamDecoder;
import top.yealike.ipe.protocol.telemetry.ProtocolTelemetry;
import top.yealike.ipe.protocol.codec.TripleDes;
import top.yealike.ipe.protocol.config.ProtocolProperties;
import top.yealike.ipe.protocol.service.DeviceService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** 云快充 TCP 服务端。 */
@Component
public class ProtocolServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ProtocolServer.class);

    private final ProtocolTelemetry telemetry;
    private final ProtocolProperties properties;
    private final SessionRegistry sessions;
    private final ProtocolDispatcher dispatcher;
    private final DeviceService deviceService;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean running;

    public ProtocolServer(ProtocolProperties properties, SessionRegistry sessions,
                          ProtocolDispatcher dispatcher, DeviceService deviceService, ProtocolTelemetry telemetry) {
        this.telemetry = telemetry;
        this.properties = properties;
        this.sessions = sessions;
        this.dispatcher = dispatcher;
        this.deviceService = deviceService;
    }

    @Override
    public void start() {
        byte[] key = TripleDes.keyFromHex(properties.getCryptoKeyHex());
        bossGroup = new NioEventLoopGroup(properties.getBossThreads());
        workerGroup = new NioEventLoopGroup(properties.getWorkerThreads());
        ProtocolChannelHandler handler = new ProtocolChannelHandler(sessions, dispatcher, deviceService, telemetry);
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 256)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast("idle", new IdleStateHandler(
                                    properties.getHeartbeatTimeoutSeconds(), 0, 0, TimeUnit.SECONDS));
                            ch.pipeline().addLast("frameDecoder", new FrameStreamDecoder(key));
                            ch.pipeline().addLast("protocol", handler);
                        }
                    });
            serverChannel = bootstrap.bind(properties.getTcpPort()).sync().channel();
            running = true;
            log.info("云快充平台 TCP 服务已启动，端口 {}", properties.getTcpPort());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdownGroups();
            throw new IllegalStateException("TCP 服务启动被中断", e);
        } catch (RuntimeException e) {
            shutdownGroups();
            throw e;
        }
    }

    @Override
    public void stop() {
        running = false;
        if (serverChannel != null) {
            serverChannel.close();
        }
        shutdownGroups();
        log.info("云快充平台 TCP 服务已停止");
    }

    private void shutdownGroups() {
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}