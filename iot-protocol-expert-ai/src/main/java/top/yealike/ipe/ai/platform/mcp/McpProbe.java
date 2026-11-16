package top.yealike.ipe.ai.platform.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import top.yealike.ipe.ai.contract.ComponentDigests;
import top.yealike.ipe.ai.contract.ErrorCodes;
import top.yealike.ipe.ai.contract.McpToolDescriptor;
import top.yealike.ipe.ai.support.AiExceptions;
import top.yealike.ipe.ai.support.JsonSupport;

/** 用官方 MCP SDK 做临时握手与全分页工具发现，不执行任何业务工具。 */
@Component
public class McpProbe {

    private static final int MAX_PAGES = 20;
    private static final int MAX_TOOLS = 200;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    public ProbeResult probe(String endpoint, String authorization, int timeoutMs) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (RuntimeException exception) {
            throw AiExceptions.unprocessable(ErrorCodes.MCP_INIT_FAILED, "MCP 入口地址无法解析");
        }
        if (uri.getHost() == null || !List.of("http", "https").contains(uri.getScheme())) {
            throw AiExceptions.unprocessable(ErrorCodes.MCP_INIT_FAILED, "MCP 入口必须是 http(s) 地址");
        }
        String base = uri.getScheme() + "://" + uri.getAuthority();
        String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
        Duration timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
        long started = System.nanoTime();
        McpSyncClient client = null;
        try {
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(base)
                    .endpoint(path)
                    .connectTimeout(Duration.ofSeconds(3))
                    .httpRequestCustomizer((builder, method, requestUri, body, context) -> {
                        if (authorization != null && !authorization.isBlank()) {
                            builder.header("Authorization", authorization);
                        }
                    })
                    .build();
            client = McpClient.sync(transport)
                    .requestTimeout(timeout)
                    .initializationTimeout(timeout)
                    .build();
            McpSchema.InitializeResult init = client.initialize();
            List<McpToolDescriptor> tools = listAll(client);
            String serialized = JsonSupport.write(tools);
            if (serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
                throw AiExceptions.unprocessable(ErrorCodes.MCP_INIT_FAILED, "MCP 工具目录超过 2 MiB 上限");
            }
            return new ProbeResult(
                    init.serverInfo() == null ? "" : init.serverInfo().name(),
                    init.serverInfo() == null ? "" : init.serverInfo().version(),
                    init.protocolVersion(),
                    tools,
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
        } catch (AiExceptions.AiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw AiExceptions.unprocessable(ErrorCodes.MCP_INIT_FAILED, "MCP 初始化或 tools/list 失败");
        } finally {
            if (client != null) {
                try {
                    client.closeGracefully();
                } catch (RuntimeException ignored) {
                    // cleanup is best effort
                }
            }
        }
    }

    private List<McpToolDescriptor> listAll(McpSyncClient client) {
        List<McpToolDescriptor> tools = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            McpSchema.ListToolsResult result = cursor == null ? client.listTools() : client.listTools(cursor);
            for (McpSchema.Tool tool : result.tools()) {
                tools.add(descriptor(tool));
                if (tools.size() > MAX_TOOLS) {
                    throw AiExceptions.unprocessable(ErrorCodes.MCP_INIT_FAILED, "MCP 工具数量超过 200 上限");
                }
            }
            cursor = result.nextCursor();
            if (cursor == null || cursor.isBlank()) {
                return List.copyOf(tools);
            }
            if (!cursors.add(cursor)) {
                throw AiExceptions.unprocessable(ErrorCodes.MCP_INIT_FAILED, "MCP 工具分页游标重复");
            }
        }
        throw AiExceptions.unprocessable(ErrorCodes.MCP_INIT_FAILED, "MCP 工具分页超过 20 页上限");
    }

    private static McpToolDescriptor descriptor(McpSchema.Tool tool) {
        Map<String, Object> input = inputSchema(tool.inputSchema());
        Map<String, Object> output = tool.outputSchema() == null ? Map.of() : new LinkedHashMap<>(tool.outputSchema());
        Map<String, Object> annotations = annotations(tool.annotations());
        boolean readOnly = Boolean.TRUE.equals(annotations.get("readOnlyHint"));
        return new McpToolDescriptor(
                tool.name(),
                tool.description() == null ? "" : tool.description(),
                input,
                output,
                annotations,
                ComponentDigests.mcpTool(input),
                readOnly);
    }

    private static Map<String, Object> inputSchema(McpSchema.JsonSchema schema) {
        if (schema == null) {
            return Map.of();
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", schema.type() == null ? "object" : schema.type());
        input.put("properties", schema.properties() == null ? Map.of() : schema.properties());
        if (schema.required() != null) {
            input.put("required", schema.required());
        }
        if (schema.additionalProperties() != null) {
            input.put("additionalProperties", schema.additionalProperties());
        }
        if (schema.defs() != null && !schema.defs().isEmpty()) {
            input.put("$defs", schema.defs());
        }
        if (schema.definitions() != null && !schema.definitions().isEmpty()) {
            input.put("definitions", schema.definitions());
        }
        return input;
    }

    private static Map<String, Object> annotations(McpSchema.ToolAnnotations annotations) {
        if (annotations == null) {
            return Map.of();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (annotations.title() != null) {
            data.put("title", annotations.title());
        }
        if (annotations.readOnlyHint() != null) {
            data.put("readOnlyHint", annotations.readOnlyHint());
        }
        if (annotations.destructiveHint() != null) {
            data.put("destructiveHint", annotations.destructiveHint());
        }
        if (annotations.idempotentHint() != null) {
            data.put("idempotentHint", annotations.idempotentHint());
        }
        if (annotations.openWorldHint() != null) {
            data.put("openWorldHint", annotations.openWorldHint());
        }
        return data;
    }

    public record ProbeResult(
            String serverName,
            String serverVersion,
            String protocolVersion,
            List<McpToolDescriptor> tools,
            long latencyMs) {}
}
