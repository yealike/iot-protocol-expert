package top.yealike.ipe.ai.gateway;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 暴露 Nacos MCP 目录的解析结果。
 *
 * <p>目录不可用不会让 AI 服务自身下线：MCP 调用失败已由工具层降级，这里只把服务名、版本、入口与漂移原因放进健康详情。
 */
@Component
public class McpRegistryHealthIndicator implements HealthIndicator {
    private final McpEndpointResolver resolver;

    public McpRegistryHealthIndicator(McpEndpointResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Health health() {
        var snapshot = resolver.snapshot();
        var builder = Health.up()
                .withDetail("server", snapshot.serverName())
                .withDetail("source", snapshot.source())
                .withDetail("endpoint", String.valueOf(snapshot.endpoint()))
                .withDetail("tools", snapshot.tools().size());
        if (snapshot.version() != null) builder.withDetail("version", snapshot.version());
        if (snapshot.problem() != null) builder.withDetail("problem", snapshot.problem());
        return builder.build();
    }
}
