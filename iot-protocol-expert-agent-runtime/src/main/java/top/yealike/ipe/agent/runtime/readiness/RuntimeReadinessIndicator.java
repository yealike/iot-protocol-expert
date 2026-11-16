package top.yealike.ipe.agent.runtime.readiness;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import top.yealike.ipe.agent.runtime.config.RuntimeProperties;
import top.yealike.ipe.agent.runtime.control.ControlClient;
import top.yealike.ipe.agent.runtime.prepare.PreparedReleaseRegistry;
import top.yealike.ipe.agent.runtime.registry.NacosRuntimeRegistry;

/**
 * Runtime readiness：密钥配置、Nacos 基础注册和 Control 可达性。
 *
 * <p>模型 Provider 短暂故障只影响具体 Run，不应让 Runtime 从注册中心下线。
 */
@Component("runtimeReadiness")
public class RuntimeReadinessIndicator implements HealthIndicator {

    private static final Duration CONTROL_CACHE = Duration.ofSeconds(5);

    private final RuntimeProperties properties;
    private final NacosRuntimeRegistry nacos;
    private final ControlClient control;
    private final PreparedReleaseRegistry releases;
    private volatile Instant checkedAt = Instant.EPOCH;
    private volatile boolean controlReachable;

    public RuntimeReadinessIndicator(
            RuntimeProperties properties,
            NacosRuntimeRegistry nacos,
            ControlClient control,
            PreparedReleaseRegistry releases) {
        this.properties = properties;
        this.nacos = nacos;
        this.control = control;
        this.releases = releases;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        boolean secretConfigured = !properties.resolvedServiceKey().isBlank();
        boolean registryReady = nacos.ready();
        boolean controlReady = controlReachable();
        builder.withDetail("secretConfigured", secretConfigured)
                .withDetail("nacosEnabled", nacos.enabled())
                .withDetail("nacosReady", registryReady)
                .withDetail("controlReachable", controlReady)
                .withDetail("instanceId", nacos.instanceId())
                .withDetail("preparedReleases", releases.all().size());
        if (!secretConfigured) {
            return builder.down().withDetail("reason", "内部服务密钥未配置").build();
        }
        if (!registryReady) {
            return builder.down().withDetail("reason", "Nacos 基础服务未注册").build();
        }
        if (!controlReady) {
            return builder.down().withDetail("reason", "Control 不可达").build();
        }
        return builder.build();
    }

    private boolean controlReachable() {
        Instant now = Instant.now();
        if (Duration.between(checkedAt, now).compareTo(CONTROL_CACHE) < 0) {
            return controlReachable;
        }
        controlReachable = control.readiness();
        checkedAt = now;
        return controlReachable;
    }
}
