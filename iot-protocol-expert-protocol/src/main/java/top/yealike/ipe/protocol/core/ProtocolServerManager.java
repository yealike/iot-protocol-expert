package top.yealike.ipe.protocol.core;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 统一启停各协议服务端。协议自身不直接参与 Spring 生命周期。 */
@Component
public class ProtocolServerManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ProtocolServerManager.class);

    private final List<ProtocolServer> servers;
    private volatile boolean running;

    public ProtocolServerManager(List<ProtocolServer> servers) {
        this.servers = servers;
    }

    @Override
    public void start() {
        for (ProtocolServer server : servers) {
            if (!server.enabled()) {
                log.info("协议 {} 未启用，跳过监听 {}", server.protocol().label(), server.port());
                continue;
            }
            try {
                server.start();
            } catch (RuntimeException e) {
                log.error("协议 {} 监听 {} 启动失败", server.protocol().label(), server.port(), e);
                throw e;
            }
        }
        running = true;
        log.info(
                "多协议服务端已启动：{}",
                servers.stream()
                        .filter(ProtocolServer::isRunning)
                        .map(s -> s.protocol().label() + ":" + s.port())
                        .toList());
    }

    @Override
    public void stop() {
        for (int i = servers.size() - 1; i >= 0; i--) {
            ProtocolServer server = servers.get(i);
            if (server.isRunning()) {
                server.stop();
            }
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
