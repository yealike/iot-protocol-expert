package top.yealike.pep.control.integration;

import java.net.URI;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import top.yealike.pep.control.config.properties.AiProperties;
import top.yealike.pep.control.integration.dto.Snapshot;

/** 系统级 OPS 工具入口由部署配置指向 Higress；组件版本由平台发布清单管理。 */
@Component
public class McpEndpointResolver {
    private final Snapshot snapshot;

    public McpEndpointResolver(AiProperties ai) {
        String configured = ai.mcp().url();
        URI endpoint = configured != null && !configured.isBlank()
                ? requireHttp(configured)
                : ai.mcp().gatewayBaseUrl() == null || ai.mcp().gatewayBaseUrl().isBlank()
                        ? null
                        : gatewayEndpoint(
                                ai.mcp().gatewayBaseUrl(), ai.mcp().registry().serverName());
        snapshot = new Snapshot(
                ai.mcp().registry().serverName(),
                null,
                endpoint,
                Set.of(),
                "higress",
                endpoint == null ? "请配置 IPE_AI_MCP_URL 或 IPE_AI_MCP_GATEWAY_BASE" : null);
    }

    public URI endpoint() {
        if (snapshot.endpoint() == null) throw new IllegalStateException(snapshot.problem());
        return snapshot.endpoint();
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    static URI gatewayEndpoint(String gatewayBase, String serverName) {
        String base = gatewayBase.endsWith("/") ? gatewayBase.substring(0, gatewayBase.length() - 1) : gatewayBase;
        return requireHttp(base + "/ipe-mcp/" + serverName);
    }

    static URI requireHttp(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少 MCP 入口地址");
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("MCP 入口地址无法解析：" + value, ex);
        }
        if (!List.of("http", "https").contains(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("MCP 入口地址必须是 http(s) 地址：" + value);
        }
        return uri;
    }
}
