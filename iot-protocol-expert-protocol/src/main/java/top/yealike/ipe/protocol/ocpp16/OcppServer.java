package top.yealike.ipe.protocol.ocpp16;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.yealike.ipe.protocol.config.ProtocolProperties;
import top.yealike.ipe.protocol.core.ProtocolServer;
import top.yealike.ipe.protocol.core.ProtocolType;
import top.yealike.ipe.protocol.ops.OpsClient;

/** OCPP 1.6J WebSocket 服务端：ws://host:port{path}/{桩号}。 */
@Component
public class OcppServer implements ProtocolServer {

    private static final Logger log = LoggerFactory.getLogger(OcppServer.class);

    private final ProtocolProperties properties;
    private final OcppSessionRegistry sessions;
    private final OcppDispatcher dispatcher;
    private final OcppTelemetry telemetry;
    private final OpsClient ops;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private EventExecutorGroup businessGroup;
    private Channel serverChannel;
    private volatile boolean running;

    public OcppServer(
            ProtocolProperties properties,
            OcppSessionRegistry sessions,
            OcppDispatcher dispatcher,
            OcppTelemetry telemetry,
            OpsClient ops) {
        this.properties = properties;
        this.sessions = sessions;
        this.dispatcher = dispatcher;
        this.telemetry = telemetry;
        this.ops = ops;
    }

    @Override
    public ProtocolType protocol() {
        return ProtocolType.OCPP16J;
    }

    @Override
    public boolean enabled() {
        return properties.isOcppEnabled();
    }

    @Override
    public int port() {
        return properties.getOcppPort();
    }

    @Override
    public void start() {
        String path = normalizePath(properties.getOcppPath());
        bossGroup = new NioEventLoopGroup(properties.getBossThreads());
        workerGroup = new NioEventLoopGroup(properties.getWorkerThreads());
        businessGroup = new DefaultEventExecutorGroup(properties.getWorkerThreads());
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 256)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast("http", new HttpServerCodec());
                            ch.pipeline().addLast("aggregator", new HttpObjectAggregator(65536));
                            ch.pipeline()
                                    .addLast(
                                            "idle",
                                            new IdleStateHandler(
                                                    properties.getOcppIdleTimeoutSeconds(), 0, 0, TimeUnit.SECONDS));
                            ch.pipeline()
                                    .addLast(
                                            "websocket",
                                            new WebSocketServerProtocolHandler(
                                                    path, "ocpp1.6", true, 65536, false, true));
                            ch.pipeline()
                                    .addLast(
                                            businessGroup,
                                            "ocpp",
                                            new OcppChannelHandler(path, sessions, dispatcher, telemetry, ops));
                        }
                    });
            serverChannel = bootstrap.bind(properties.getOcppPort()).sync().channel();
            running = true;
            log.info("OCPP 1.6J 服务已启动：ws://0.0.0.0:{}{}/{chargePointId}", properties.getOcppPort(), path);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdownGroups();
            throw new IllegalStateException("OCPP 服务启动被中断", e);
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
        log.info("OCPP 1.6J 服务已停止");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void shutdownGroups() {
        if (businessGroup != null) {
            businessGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/ocpp";
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }
}
