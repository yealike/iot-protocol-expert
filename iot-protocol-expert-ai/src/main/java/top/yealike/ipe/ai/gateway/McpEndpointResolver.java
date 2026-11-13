package top.yealike.ipe.ai.gateway;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.listener.AbstractNacosMcpServerListener;
import com.alibaba.nacos.api.ai.listener.NacosMcpServerEvent;
import com.alibaba.nacos.api.ai.model.mcp.McpEndpointInfo;
import com.alibaba.nacos.api.ai.model.mcp.McpServerDetailInfo;
import com.alibaba.nacos.api.ai.model.mcp.McpTool;
import com.alibaba.nacos.api.exception.NacosException;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.yealike.ipe.ai.config.AiProperties;
import top.yealike.ipe.ai.tool.ToolNames;

/**
 * 从 Nacos MCP Registry 订阅运维 MCP 服务，解析出实际入口地址。
 *
 * <p>目录是运维 MCP 的优先事实来源：服务名、版本与工具清单以注册结果为准，入口优先使用目录前端地址；
 * 目录未提供入口时再由网关根地址与服务名拼出。目录不可用或服务未注册时回退到 {@code ipe.ai.mcp.url}，
 * 并把原因记进快照与日志。
 */
@Component
public class McpEndpointResolver implements SmartInitializingSingleton {
    private static final Logger log = LoggerFactory.getLogger(McpEndpointResolver.class);
    private static final String ROUTE_PREFIX = "/ipe-mcp/";

    private final AiProperties ai;
    private final String serverAddr;
    private final String username;
    private final String password;
    private final AbstractNacosMcpServerListener listener = new AbstractNacosMcpServerListener() {
        @Override
        public void onEvent(NacosMcpServerEvent event) {
            publish(event.getMcpServerDetailInfo());
        }
    };

    private final Object subscriptionMonitor = new Object();
    private AiService aiService;
    private boolean subscribed;
    private Snapshot snapshot;

    public McpEndpointResolver(
            AiProperties ai,
            @Value("${spring.cloud.nacos.server-addr:}") String serverAddr,
            @Value("${spring.cloud.nacos.username:}") String username,
            @Value("${spring.cloud.nacos.password:}") String password) {
        this.ai = ai;
        this.serverAddr = serverAddr;
        this.username = username;
        this.password = password;
        this.snapshot =
                new Snapshot(ai.mcp().registry().serverName(), null, staticEndpoint(ai), Set.of(), "url", "目录未启用");
        if (!ai.mcp().registry().usable()) log.info("Nacos MCP 目录未启用，使用静态 MCP 入口 {}", snapshot.endpoint());
    }

    public URI endpoint() {
        URI endpoint = snapshot.endpoint();
        if (endpoint == null) throw new IllegalStateException("MCP 入口尚未解析：" + snapshot.problem());
        return endpoint;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (ai.mcp().registry().usable()) subscribe();
    }

    /** 目录订阅失败、服务尚未注册或目录校验未通过时定期重试；订阅有效后由 Nacos 推送变更。 */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void reconcile() {
        if (ai.mcp().registry().usable()
                && (snapshot.source().equals("url") || snapshot.problem() != null)) {
            subscribe();
        }
    }

    @PreDestroy
    public synchronized void close() {
        AiService service = aiService;
        aiService = null;
        subscribed = false;
        if (service != null) close(service);
    }

    private void subscribe() {
        synchronized (subscriptionMonitor) {
            if (subscribed) return;
            if (serverAddr.isBlank()) {
                log.warn(
                        "Nacos MCP 目录已启用，但缺少 spring.cloud.nacos.server-addr，暂用静态入口 {}",
                        snapshot.endpoint());
                return;
            }
            String serverName = ai.mcp().registry().serverName();
            AiService service = null;
            try {
                service = AiFactory.createAiService(properties());
                McpServerDetailInfo detail = service.subscribeMcpServer(serverName, listener);
                if (detail == null) {
                    log.warn("Nacos MCP 目录中不存在服务 {}，暂用静态入口 {}", serverName, snapshot.endpoint());
                    close(service);
                    return;
                }
                publish(detail);
                if (snapshot.problem() == null) {
                    aiService = service;
                    subscribed = true;
                } else {
                    close(service);
                }
            } catch (NacosException ex) {
                log.warn("Nacos MCP 目录不可用（{}），暂用静态入口 {}", ex.getErrMsg(), snapshot.endpoint());
                if (service != null) close(service);
            } catch (RuntimeException ex) {
                log.warn("Nacos MCP 目录返回无效数据（{}），暂用静态入口 {}", ex.getMessage(), snapshot.endpoint());
                if (service != null) close(service);
            }
        }
    }

    private Properties properties() {
        var properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddr);
        properties.setProperty(PropertyKeyConst.NAMESPACE, ai.mcp().registry().namespace());
        if (!username.isBlank()) {
            properties.setProperty(PropertyKeyConst.USERNAME, username);
            properties.setProperty(PropertyKeyConst.PASSWORD, password);
        }
        return properties;
    }

    private synchronized void publish(McpServerDetailInfo detail) {
        String serverName = ai.mcp().registry().serverName();
        String version = version(detail);
        Set<String> tools = tools(detail);
        URI endpoint;
        String problem = problem(detail, version, tools);
        try {
            endpoint = endpoint(detail);
        } catch (IllegalArgumentException ex) {
            endpoint = null;
            problem = ex.getMessage();
        }
        Snapshot next = new Snapshot(serverName, version, endpoint, tools, "nacos", problem);
        // 订阅时既会返回当前详情、又会立即推送一次同一版本，去重后只记一条
        if (next.equals(snapshot)) return;
        if (problem == null) {
            log.info("Nacos MCP 目录已生效：服务 {} 版本 {}，{} 个工具，入口 {}", serverName, version, tools.size(), endpoint);
        } else {
            log.warn("Nacos MCP 目录校验未通过（{}）：服务 {} 版本 {}", problem, serverName, version);
        }
        this.snapshot = next;
    }

    private void close(AiService service) {
        try {
            service.unsubscribeMcpServer(ai.mcp().registry().serverName(), listener);
        } catch (NacosException ex) {
            log.debug("取消 MCP 目录订阅失败：{}", ex.getErrMsg());
        }
        try {
            service.shutdown();
        } catch (NacosException ex) {
            log.debug("关闭 Nacos AI 客户端失败：{}", ex.getErrMsg());
    }
    }

    private URI endpoint(McpServerDetailInfo detail) {
        return resolveEndpoint(detail, ai);
    }

    static URI resolveEndpoint(McpServerDetailInfo detail, AiProperties ai) {
        List<McpEndpointInfo> frontend = detail == null ? null : detail.getFrontendEndpoints();
        if (frontend != null && !frontend.isEmpty()) {
            return frontendEndpoint(frontend.getFirst());
        }
        String gatewayBase = ai.mcp().gatewayBaseUrl();
        if (gatewayBase != null && !gatewayBase.isBlank()) {
            return gatewayEndpoint(gatewayBase, ai.mcp().registry().serverName());
        }
        URI fallback = staticEndpoint(ai);
        if (fallback == null) throw new IllegalArgumentException("缺少 MCP 入口：未配置网关根地址与静态地址");
        return fallback;
    }

    private String problem(McpServerDetailInfo detail, String version, Set<String> tools) {
        if (!Boolean.TRUE.equals(detail.isEnabled())) return "服务在目录中未启用";
        if (detail.getFrontProtocol() != null
                && Arrays.stream(detail.getFrontProtocol().split(","))
                        .map(String::strip)
                        .noneMatch(protocol -> protocol.equalsIgnoreCase("mcp"))) {
            return "目录中的前端协议不是 MCP：" + detail.getFrontProtocol();
        }
        String expected = ai.mcp().registry().version();
        if (expected != null && !expected.isBlank() && !expected.equals(version)) {
            return "目录版本 " + version + " 与期望 " + expected + " 不一致";
        }
        var missing = new LinkedHashSet<>(ToolNames.REMOTE_TOOLS);
        missing.removeAll(tools);
        if (!missing.isEmpty()) return "目录缺少工具 " + String.join(", ", missing);
        return null;
    }

    private static String version(McpServerDetailInfo detail) {
        if (detail.getVersionDetail() != null && detail.getVersionDetail().getVersion() != null) {
            return detail.getVersionDetail().getVersion();
        }
        return detail.getVersion() == null ? "unknown" : detail.getVersion();
    }

    private static Set<String> tools(McpServerDetailInfo detail) {
        var tools = new LinkedHashSet<String>();
        if (detail.getToolSpec() != null && detail.getToolSpec().getTools() != null) {
            for (McpTool tool : detail.getToolSpec().getTools()) {
                if (tool.getName() != null) tools.add(tool.getName());
            }
        }
        return Set.copyOf(tools);
    }

    private static URI staticEndpoint(AiProperties ai) {
        String url = ai.mcp().url();
        return url == null || url.isBlank() ? null : requireHttp(url);
    }

    /** 网关根地址 + 固定路由前缀 + Nacos 目录中的服务名。 */
    static URI gatewayEndpoint(String gatewayBase, String serverName) {
        String base = gatewayBase.endsWith("/") ? gatewayBase.substring(0, gatewayBase.length() - 1) : gatewayBase;
        return requireHttp(base + ROUTE_PREFIX + serverName);
    }

    static URI frontendEndpoint(McpEndpointInfo endpoint) {
        String scheme = endpoint.getProtocol() != null && endpoint.getProtocol().contains("https") ? "https" : "http";
        String path = endpoint.getPath() == null || endpoint.getPath().isBlank() ? "" : endpoint.getPath();
        return requireHttp(scheme + "://" + endpoint.getAddress() + ":" + endpoint.getPort() + path);
    }

    /** 校验入口是可直连的 http(s) 地址：不带用户信息、片段或其他协议。 */
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

    /** 一次目录快照：入口、版本、工具清单与校验结论。 */
    public record Snapshot(
            String serverName, String version, URI endpoint, Set<String> tools, String source, String problem) {}
}
