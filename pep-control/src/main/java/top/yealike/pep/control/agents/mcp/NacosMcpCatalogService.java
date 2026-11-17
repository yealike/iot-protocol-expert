package top.yealike.pep.control.agents.mcp;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.mcp.McpEndpointInfo;
import com.alibaba.nacos.api.ai.model.mcp.McpServerDetailInfo;
import com.alibaba.nacos.api.ai.model.mcp.McpTool;
import com.alibaba.nacos.api.exception.NacosException;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.stereotype.Service;
import top.yealike.pep.control.agents.config.PlatformProperties;
import top.yealike.pep.control.support.AiExceptions;

/** 读取现有 Nacos MCP 原生目录，将其作为运维 MCP 的登记来源。 */
@Service
public class NacosMcpCatalogService {

    private final PlatformProperties properties;
    private final Object monitor = new Object();
    private volatile AiService aiService;

    public NacosMcpCatalogService(PlatformProperties properties) {
        this.properties = properties;
    }

    public CatalogEntry fetch(String serverName, String namespace, String expectedVersion) {
        if (!properties.registry().enabled()) {
            throw AiExceptions.unavailable("Nacos 注册中心未启用");
        }
        String actualNamespace =
                namespace == null || namespace.isBlank() ? properties.registry().namespace() : namespace;
        try {
            McpServerDetailInfo detail = service()
                    .getMcpServer(
                            serverName, expectedVersion == null || expectedVersion.isBlank() ? null : expectedVersion);
            if (detail == null) {
                throw AiExceptions.unavailable("Nacos MCP 目录中不存在服务：" + serverName);
            }
            if (!detail.isEnabled()) {
                throw AiExceptions.unavailable("Nacos MCP 服务未启用：" + serverName);
            }
            String version = detail.getVersionDetail() == null
                            || detail.getVersionDetail().getVersion() == null
                    ? detail.getVersion()
                    : detail.getVersionDetail().getVersion();
            if (expectedVersion != null && !expectedVersion.isBlank() && !expectedVersion.equals(version)) {
                throw AiExceptions.unavailable("Nacos MCP 目录版本与期望不一致");
            }
            URI endpoint = resolveEndpoint(detail, actualNamespace);
            List<Map<String, Object>> tools = new ArrayList<>();
            if (detail.getToolSpec() != null && detail.getToolSpec().getTools() != null) {
                for (McpTool tool : detail.getToolSpec().getTools()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", tool.getName());
                    row.put("description", tool.getDescription() == null ? "" : tool.getDescription());
                    row.put("inputSchema", tool.getInputSchema() == null ? Map.of() : tool.getInputSchema());
                    tools.add(row);
                }
            }
            return new CatalogEntry(serverName, version, endpoint.toString(), tools);
        } catch (NacosException exception) {
            throw AiExceptions.unavailable("Nacos MCP 目录不可用：" + exception.getErrMsg());
        }
    }

    private URI resolveEndpoint(McpServerDetailInfo detail, String ignoredNamespace) {
        List<McpEndpointInfo> frontend = detail.getFrontendEndpoints();
        if (frontend != null && !frontend.isEmpty()) {
            McpEndpointInfo endpoint = frontend.getFirst();
            String protocol =
                    endpoint.getProtocol() != null && endpoint.getProtocol().contains("https") ? "https" : "http";
            String path = endpoint.getPath() == null || endpoint.getPath().isBlank() ? "" : endpoint.getPath();
            return URI.create(protocol + "://" + endpoint.getAddress() + ":" + endpoint.getPort() + path);
        }
        String gateway = properties.registry().mcpGatewayBase();
        if (gateway == null || gateway.isBlank()) {
            throw AiExceptions.unavailable("MCP 目录未提供前端入口，且未配置网关根地址");
        }
        String base = gateway.endsWith("/") ? gateway.substring(0, gateway.length() - 1) : gateway;
        return URI.create(base + "/ipe-mcp/" + detail.getName());
    }

    private AiService service() throws NacosException {
        AiService current = aiService;
        if (current != null) {
            return current;
        }
        synchronized (monitor) {
            if (aiService == null) {
                Properties nacos = new Properties();
                nacos.setProperty(
                        PropertyKeyConst.SERVER_ADDR, properties.registry().serverAddr());
                nacos.setProperty(
                        PropertyKeyConst.NAMESPACE, properties.registry().namespace());
                if (properties.registry().username() != null
                        && !properties.registry().username().isBlank()) {
                    nacos.setProperty(
                            PropertyKeyConst.USERNAME, properties.registry().username());
                    nacos.setProperty(
                            PropertyKeyConst.PASSWORD, properties.registry().password());
                }
                aiService = AiFactory.createAiService(nacos);
            }
            return aiService;
        }
    }

    @PreDestroy
    public void close() {
        AiService current = aiService;
        aiService = null;
        if (current != null) {
            try {
                current.shutdown();
            } catch (NacosException ignored) {
                // shutdown is best effort
            }
        }
    }

    public record CatalogEntry(String serverName, String version, String endpoint, List<Map<String, Object>> tools) {}
}
