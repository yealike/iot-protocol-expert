package top.yealike.pep.control.agents.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.yealike.pep.control.agents.mcp.domain.ProbeResult;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.control.support.JsonSupport;
import top.yealike.pep.runtime.contract.ComponentEndpoint;
import top.yealike.pep.runtime.contract.EndpointPolicy;
import top.yealike.pep.runtime.contract.ErrorCodes;
import top.yealike.pep.runtime.contract.McpToolDescriptor;

/** 用官方 MCP SDK 做临时握手与全分页工具发现，不执行任何业务工具。 */
@Component
public class McpProbe {

    private static final Logger log = LoggerFactory.getLogger(McpProbe.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final int MAX_CATALOG_BYTES = 2 * 1024 * 1024;
    private static final String CLIENT_NAME = "ipe-agent-control";
    private static final String CLIENT_VERSION = "1.0.0";

    public ProbeResult probe(String endpoint, String authorization, int timeoutMs) {
        ComponentEndpoint target = resolveEndpoint(endpoint);
        Duration timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
        long started = System.nanoTime();
        long deadline = started + timeout.toNanos();
        McpSyncClient client = null;
        try {
            client = openClient(target, authorization, timeout);
            McpSchema.InitializeResult init = client.initialize();
            List<McpToolDescriptor> tools = McpToolCatalog.readAll(client, deadline);
            ensureCatalogSize(tools);
            McpSchema.Implementation serverInfo = init.serverInfo();
            return new ProbeResult(
                    serverInfo == null ? "" : serverInfo.name(),
                    serverInfo == null ? "" : serverInfo.version(),
                    init.protocolVersion(),
                    tools,
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
        } catch (AiExceptions.AiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("MCP 探测失败 host={} error={}", target.baseUrl(), rootMessage(exception));
            log.debug("MCP 探测失败详情", exception);
            throw AiExceptions.unprocessable(ErrorCodes.MCP_INIT_FAILED, "MCP 初始化或 tools/list 失败");
        } finally {
            closeQuietly(client);
        }
    }

    private static ComponentEndpoint resolveEndpoint(String endpoint) {
        try {
            return EndpointPolicy.requireAllowedResolved(endpoint);
        } catch (IllegalArgumentException exception) {
            throw AiExceptions.unprocessable(ErrorCodes.MCP_INIT_FAILED, "MCP 入口地址无法解析");
        }
    }

    private static McpSyncClient openClient(ComponentEndpoint target, String authorization, Duration timeout) {
        String path = target.path().isBlank() ? "/" : target.path();
        if (!target.query().isBlank()) {
            path = path + "?" + target.query();
        }
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(target.baseUrl())
                .endpoint(path)
                .connectTimeout(CONNECT_TIMEOUT)
                .httpRequestCustomizer((request, method, uri, body, context) -> {
                    if (authorization != null && !authorization.isBlank()) {
                        request.header("Authorization", authorization);
                    }
                })
                .build();
        return McpClient.sync(transport)
                .requestTimeout(timeout)
                .initializationTimeout(timeout)
                .clientInfo(new McpSchema.Implementation(CLIENT_NAME, CLIENT_VERSION))
                .build();
    }

    private static void ensureCatalogSize(List<McpToolDescriptor> tools) {
        String serialized = JsonSupport.write(tools);
        if (serialized.getBytes(StandardCharsets.UTF_8).length > MAX_CATALOG_BYTES) {
            throw AiExceptions.unprocessable(ErrorCodes.MCP_INIT_FAILED, "MCP 工具目录超过 2 MiB 上限");
        }
    }

    private static String rootMessage(Throwable exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    private static void closeQuietly(McpSyncClient client) {
        if (client == null) {
            return;
        }
        try {
            client.closeGracefully();
        } catch (RuntimeException exception) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                log.debug("MCP 探测客户端关闭失败", exception);
            }
        }
    }
}
