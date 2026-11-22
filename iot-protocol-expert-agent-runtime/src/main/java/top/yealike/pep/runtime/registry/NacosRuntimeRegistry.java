package top.yealike.pep.runtime.registry;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import jakarta.annotation.PreDestroy;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.yealike.pep.runtime.config.RuntimeProperties;
import top.yealike.pep.runtime.contract.ComponentEndpoint;
import top.yealike.pep.runtime.contract.InternalPaths;

/**
 * Runtime 的 Nacos 注册。
 *
 * <p>基础服务可用于 Runtime 部署实例的发现。
 * 业务流量不经过 Nacos。注册失败只影响 readiness，不在 JVM 内反复重启。
 */
@Component
public class NacosRuntimeRegistry {

    private static final Logger log = LoggerFactory.getLogger(NacosRuntimeRegistry.class);

    private final RuntimeProperties properties;
    private final String instanceId = "runtime-" + UUID.randomUUID().toString().substring(0, 8);
    private final Object monitor = new Object();

    private volatile NamingService namingService;
    private volatile String advertiseIp;
    private volatile boolean baseRegistered;
    private volatile boolean ready;

    public NacosRuntimeRegistry(RuntimeProperties properties) {
        this.properties = properties;
    }

    public String instanceId() {
        return instanceId;
    }

    public boolean ready() {
        if (!properties.nacos().enabled()) {
            // 本地双进程/关闭注册中心时，不连接 Nacos；只要 Control 地址与内部密钥可用即可接单。
            return properties.usable();
        }
        return ready && baseRegistered;
    }

    public boolean enabled() {
        return properties.nacos().enabled();
    }

    public String advertiseIp() {
        return advertiseIp;
    }

    /** 从当前环境的 Nacos 健康实例中解析组件基地址；未启用或无实例时返回 null。 */
    public String discoverBaseUrl(String serviceName) {
        ComponentEndpoint endpoint = discoverEndpoint(serviceName);
        return endpoint == null ? null : endpoint.baseUrl();
    }

    /** 从当前环境的 Nacos 健康实例中解析完整入口，保留 metadata 中的 scheme/path/query。 */
    public ComponentEndpoint discoverEndpoint(String serviceName) {
        if (!properties.nacos().enabled() || serviceName == null || serviceName.isBlank()) {
            return null;
        }
        try {
            ensureNamingService();
            var instances = namingService.selectInstances(
                    serviceName, properties.nacos().group(), true);
            var healthy = instances.stream()
                    .filter(Instance::isHealthy)
                    .filter(Instance::isEnabled)
                    .toList();
            if (healthy.isEmpty()) {
                return null;
            }
            Instance instance = healthy.get(ThreadLocalRandom.current().nextInt(healthy.size()));
            return endpointOf(instance);
        } catch (NacosException | RuntimeException exception) {
            log.debug("component discovery failed: service={} reason={}", serviceName, exception.getMessage());
            return null;
        }
    }

    static ComponentEndpoint endpointOf(Instance instance) {
        Map<String, String> metadata = instance.getMetadata() == null ? Map.of() : instance.getMetadata();
        String scheme = metadata.get("scheme");
        return ComponentEndpoint.of(
                scheme == null || scheme.isBlank() ? "http" : scheme,
                instance.getIp(),
                instance.getPort(),
                metadata.get("path"),
                metadata.get("query"));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!properties.nacos().enabled()) {
            ready = properties.usable();
            log.info("Nacos registration disabled; base runtime service is not registered");
            return;
        }
        registerBase();
    }

    /** Nacos 短暂不可用时定期重试基础注册。 */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void reconcile() {
        if (!properties.nacos().enabled()) {
            ready = properties.usable();
            return;
        }
        if (!serverAvailable()) {
            if (ready || baseRegistered) {
                log.warn("Nacos server is unavailable; runtime readiness is down");
            }
            ready = false;
            baseRegistered = false;
            if (namingService == null) {
                // 冷启动时 Nacos 可能尚未可用；每个 reconcile 周期重试创建客户端，
                // 不让一次失败永久关闭注册能力。
                registerBase();
            }
            return;
        }
        if (!baseRegistered) {
            registerBase();
        }
    }

    private void registerBase() {
        synchronized (monitor) {
            try {
                ensureNamingService();
                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put("path", InternalPaths.RUNTIME_BASE);
                metadata.put("runtimeInstance", instanceId);
                namingService.registerInstance(
                        InternalPaths.RUNTIME_BASE_SERVICE, properties.nacos().group(), instance(metadata));
                boolean nacosUp = "UP".equalsIgnoreCase(namingService.getServerStatus());
                baseRegistered = nacosUp;
                ready = nacosUp;
                log.info(
                        "runtime base service registered: service={} ip={} port={} nacosUp={}",
                        InternalPaths.RUNTIME_BASE_SERVICE,
                        advertiseIp,
                        properties.port(),
                        nacosUp);
            } catch (NacosException | RuntimeException exception) {
                baseRegistered = false;
                ready = false;
                log.warn("runtime base registration failed: {}", exception.getMessage());
            }
        }
    }

    private void ensureNamingService() {
        if (namingService != null) {
            return;
        }
        synchronized (monitor) {
            if (namingService != null) {
                return;
            }
            try {
                Properties nacos = new Properties();
                nacos.setProperty(
                        PropertyKeyConst.SERVER_ADDR, properties.nacos().serverAddr());
                nacos.setProperty(PropertyKeyConst.NAMESPACE, properties.nacos().namespace());
                if (!blank(properties.nacos().username())) {
                    nacos.setProperty(
                            PropertyKeyConst.USERNAME, properties.nacos().username());
                    nacos.setProperty(
                            PropertyKeyConst.PASSWORD, properties.nacos().password());
                }
                namingService = NacosFactory.createNamingService(nacos);
                advertiseIp = resolveAdvertiseIp(properties.advertiseIp());
            } catch (NacosException exception) {
                ready = false;
                throw new IllegalStateException("Nacos Naming 初始化失败");
            }
        }
    }

    private Instance instance(Map<String, String> metadata) {
        Instance instance = new Instance();
        instance.setIp(advertiseIp);
        instance.setPort(properties.port());
        instance.setWeight(1.0);
        instance.setHealthy(true);
        instance.setEnabled(true);
        instance.setEphemeral(true);
        instance.setInstanceId(instanceId);
        instance.setMetadata(new LinkedHashMap<>(metadata));
        return instance;
    }

    private void deregister(String serviceName) {
        NamingService service = namingService;
        if (service == null) {
            return;
        }
        try {
            service.deregisterInstance(serviceName, properties.nacos().group(), advertiseIp, properties.port());
        } catch (NacosException exception) {
            log.debug("deregister failed: service={} reason={}", serviceName, exception.getErrMsg());
        }
    }

    @PreDestroy
    void close() {
        ready = false;
        if (!properties.nacos().enabled()) {
            baseRegistered = false;
            return;
        }
        if (baseRegistered) {
            deregister(InternalPaths.RUNTIME_BASE_SERVICE);
        }
        NamingService service = namingService;
        if (service != null) {
            try {
                service.shutDown();
            } catch (NacosException exception) {
                log.debug("naming shutdown failed: {}", exception.getErrMsg());
            }
        }
    }

    private boolean serverAvailable() {
        NamingService service = namingService;
        if (service == null) {
            return false;
        }
        try {
            return "UP".equalsIgnoreCase(service.getServerStatus());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String resolveAdvertiseIp(String configured) {
        if (!blank(configured)) {
            return configured.strip();
        }
        try {
            return NetworkInterface.networkInterfaces()
                    .flatMap(NetworkInterface::inetAddresses)
                    .filter(address -> address instanceof Inet4Address)
                    .filter(address -> !address.isLoopbackAddress() && !address.isLinkLocalAddress())
                    .map(InetAddress::getHostAddress)
                    .findFirst()
                    .orElse(InetAddress.getLocalHost().getHostAddress());
        } catch (java.io.IOException exception) {
            return "127.0.0.1";
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
