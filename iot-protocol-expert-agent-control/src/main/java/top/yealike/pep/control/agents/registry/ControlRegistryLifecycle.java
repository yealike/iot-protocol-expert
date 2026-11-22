package top.yealike.pep.control.agents.registry;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import top.yealike.pep.control.config.properties.PlatformProperties;
import top.yealike.pep.control.integration.registry.NacosRegistryService;

/** Control 自身注册为 {@code ipe-ai-control}，供 Runtime 与应用诊断定位内部接口。 */
@Component
public class ControlRegistryLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ControlRegistryLifecycle.class);

    private final NacosRegistryService registry;
    private final PlatformProperties properties;
    private volatile boolean registered;
    private volatile int port;
    private volatile String ip;

    public ControlRegistryLifecycle(NacosRegistryService registry, PlatformProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @EventListener
    public void onWebServer(WebServerInitializedEvent event) {
        this.port = event.getWebServer().getPort();
        this.ip = registry.advertiseIp();
        register();
    }

    private void register() {
        if (!registry.enabled()) {
            log.info("Nacos registration disabled; control service not published");
            return;
        }
        try {
            registry.register(
                    properties.registry().controlServiceName(),
                    ip,
                    port,
                    Map.of(
                            "path", "/internal/ai/v1",
                            "role", "control",
                            "environment", properties.registry().namespace()));
            registered = true;
        } catch (RuntimeException exception) {
            log.warn("Control registration failed: {}", exception.getMessage());
        }
    }

    @jakarta.annotation.PreDestroy
    public void stop() {
        if (registered && ip != null) {
            registry.deregister(properties.registry().controlServiceName(), ip, port);
        }
    }
}
