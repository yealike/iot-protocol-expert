package top.yealike.pep.control.agents.registry;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.yealike.pep.control.agents.config.PlatformProperties;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.runtime.contract.ComponentNames;
import top.yealike.pep.runtime.contract.ComponentType;

/**
 * Nacos 普通 Naming 注册与发现。
 *
 * <p>只承担服务入口与健康实例发现；Skill 文本、AgentSpec 与业务流量都不进入 Nacos。
 */
@Service
public class NacosRegistryService {

    private static final Logger log = LoggerFactory.getLogger(NacosRegistryService.class);

    private final PlatformProperties properties;
    private final int serverPort;
    private final String environment;
    private final Object monitor = new Object();
    private volatile NamingService naming;

    public NacosRegistryService(
            PlatformProperties properties,
            @Value("${server.port:19082}") int serverPort,
            @Value("${ipe.environment:local}") String environment) {
        this.properties = properties;
        this.serverPort = serverPort;
        this.environment = environment;
    }

    public boolean enabled() {
        return properties.registry().enabled();
    }

    public String group() {
        return properties.registry().group();
    }

    public String namespace() {
        return properties.registry().namespace();
    }

    public String advertiseIp() {
        String configured = properties.registry().advertiseIp();
        return configured == null || configured.isBlank() ? localAddress() : configured.strip();
    }

    public int serverPort() {
        return serverPort;
    }

    public String gatewayBase() {
        return properties.registry().mcpGatewayBase();
    }

    /**
     * 注册 MCP/Skill/Agent 版本投影。业务流量不经过 Nacos，实例只承载组件入口与元数据。
     */
    public void registerComponent(
            ComponentType type,
            String key,
            String version,
            String scheme,
            String path,
            String ip,
            int port,
            String contentDigest,
            String authMode,
            Map<String, String> extra) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("componentType", type.name());
        metadata.put("componentKey", key);
        metadata.put("componentVersion", version);
        metadata.put("contractVersion", "1");
        metadata.put("scheme", scheme == null || scheme.isBlank() ? "http" : scheme);
        metadata.put("path", path == null ? "" : path);
        metadata.put("environment", environment == null ? "" : environment);
        metadata.put("providerId", "control");
        metadata.put("contentDigest", contentDigest == null ? "" : contentDigest);
        metadata.put("authMode", authMode == null ? "NONE" : authMode);
        if (extra != null) {
            extra.forEach((name, value) -> {
                if (name != null && value != null) {
                    metadata.put(name, value);
                }
            });
        }
        register(ComponentNames.serviceName(type, key, version), ip, port, metadata);
    }

    public void register(String serviceName, String ip, int port, Map<String, String> metadata) {
        NamingService service = naming();
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setHealthy(true);
        instance.setEnabled(true);
        instance.setEphemeral(true);
        instance.setWeight(1.0d);
        instance.setMetadata(metadata);
        try {
            service.registerInstance(serviceName, group(), instance);
            log.info("Nacos registered: service={} instance={}:{} metadata={}", serviceName, ip, port, metadata);
        } catch (NacosException exception) {
            throw AiExceptions.unavailable("注册中心不可用：" + serviceName);
        }
    }

    public void deregister(String serviceName, String ip, int port) {
        NamingService service = namingOrNull();
        if (service == null) {
            return;
        }
        try {
            service.deregisterInstance(serviceName, group(), ip, port);
        } catch (NacosException exception) {
            log.warn("Nacos deregister failed: service={} error={}", serviceName, exception.getErrMsg());
        }
    }

    public List<InstanceView> healthyInstances(String serviceName) {
        NamingService service = namingOrNull();
        if (service == null) {
            return List.of();
        }
        try {
            List<Instance> instances = service.selectInstances(serviceName, group(), true);
            List<InstanceView> result = new ArrayList<>();
            for (Instance instance : instances) {
                if (!instance.isHealthy() || !instance.isEnabled()) {
                    continue;
                }
                result.add(new InstanceView(
                        instance.getIp(),
                        instance.getPort(),
                        instance.getMetadata() == null ? Map.of() : Map.copyOf(instance.getMetadata())));
            }
            return List.copyOf(result);
        } catch (NacosException exception) {
            throw AiExceptions.unavailable("注册中心发现失败：" + serviceName);
        }
    }

    public InstanceView pickHealthy(String serviceName) {
        List<InstanceView> instances = healthyInstances(serviceName);
        if (instances.isEmpty()) {
            return null;
        }
        return instances.get(ThreadLocalRandom.current().nextInt(instances.size()));
    }

    public boolean hasHealthy(String serviceName) {
        return !healthyInstances(serviceName).isEmpty();
    }

    @PreDestroy
    public void close() {
        NamingService service = naming;
        naming = null;
        if (service != null) {
            try {
                service.shutDown();
            } catch (NacosException exception) {
                log.debug("Nacos naming shutdown failed: {}", exception.getErrMsg());
            }
        }
    }

    private NamingService naming() {
        if (!enabled()) {
            throw AiExceptions.unavailable("注册中心未启用");
        }
        NamingService current = naming;
        if (current != null) {
            return current;
        }
        synchronized (monitor) {
            if (naming == null) {
                naming = create();
            }
            return naming;
        }
    }

    private NamingService namingOrNull() {
        if (!enabled()) {
            return null;
        }
        try {
            return naming();
        } catch (RuntimeException exception) {
            log.warn("Nacos naming unavailable: {}", exception.getMessage());
            return null;
        }
    }

    private NamingService create() {
        Properties nacos = new Properties();
        nacos.setProperty(PropertyKeyConst.SERVER_ADDR, properties.registry().serverAddr());
        nacos.setProperty(PropertyKeyConst.NAMESPACE, properties.registry().namespace());
        if (properties.registry().username() != null
                && !properties.registry().username().isBlank()) {
            nacos.setProperty(PropertyKeyConst.USERNAME, properties.registry().username());
            nacos.setProperty(PropertyKeyConst.PASSWORD, properties.registry().password());
        }
        try {
            return NacosFactory.createNamingService(nacos);
        } catch (NacosException exception) {
            throw AiExceptions.unavailable("注册中心连接失败");
        }
    }

    static String localAddress() {
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                var network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
                    continue;
                }
                var addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address.getHostAddress().contains(".") && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception exception) {
            return "127.0.0.1";
        }
    }

    public record InstanceView(String ip, int port, Map<String, String> metadata) {}
}
