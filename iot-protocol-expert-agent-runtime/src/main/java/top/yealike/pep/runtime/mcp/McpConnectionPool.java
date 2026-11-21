package top.yealike.pep.runtime.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.yealike.pep.observability.SensitiveDataSanitizer;
import top.yealike.pep.runtime.config.RuntimeProperties;
import top.yealike.pep.runtime.contract.ComponentDigests;
import top.yealike.pep.runtime.contract.ComponentEndpoint;
import top.yealike.pep.runtime.contract.EndpointPolicy;
import top.yealike.pep.runtime.contract.ErrorCodes;
import top.yealike.pep.runtime.contract.McpDescriptor;
import top.yealike.pep.runtime.contract.McpToolDescriptor;
import top.yealike.pep.runtime.contract.McpToolSchemas;
import top.yealike.pep.runtime.prepare.PrepareException;

/**
 * MCP 连接池。
 *
 * <p>key 含服务、版本、入口与凭据版本；初始化使用 single-flight，阻塞调用走专用有界线程池。
 */
@Component
public class McpConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionPool.class);
    private static final int MAX_PAGES = 20;
    private static final int MAX_TOOLS = 200;
    private static final long VERIFY_TIMEOUT_MS = 60_000L;

    private final RuntimeProperties properties;
    private final MeterRegistry metrics;
    private final ExecutorService mcpExecutor;
    private final ObjectMapper json = new ObjectMapper();
    private final ConcurrentHashMap<String, McpConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<McpConnection>> creating = new ConcurrentHashMap<>();
    private final AtomicInteger connectionCount = new AtomicInteger();
    private final ScheduledThreadPoolExecutor cleaner = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "ai-mcp-cleaner");
        thread.setDaemon(true);
        return thread;
    });

    public McpConnectionPool(
            RuntimeProperties properties,
            MeterRegistry metrics,
            @Qualifier("runtimeMcpExecutor") ExecutorService mcpExecutor) {
        this.properties = properties;
        this.metrics = metrics;
        this.mcpExecutor = mcpExecutor;
        cleaner.setRemoveOnCancelPolicy(true);
        cleaner.scheduleWithFixedDelay(this::cleanupIdle, 60, 60, TimeUnit.SECONDS);
    }

    /** 发布准备：临时初始化、全量列举工具并校验冻结 Schema，完成后立即关闭。 */
    public void verify(McpDescriptor descriptor, String secret) {
        Future<Void> future;
        try {
            future = mcpExecutor.submit(() -> {
                verifyBlocking(descriptor, secret);
                return null;
            });
        } catch (RejectedExecutionException exception) {
            throw new PrepareException(429, ErrorCodes.RUN_BUSY, "MCP 初始化队列已满");
        }
        try {
            future.get(VERIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new PrepareException(503, ErrorCodes.MCP_INIT_FAILED, "MCP 初始化超时：" + descriptor.key());
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new PrepareException(503, ErrorCodes.MCP_INIT_FAILED, "MCP 初始化被中断");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof PrepareException prepareException) {
                throw prepareException;
            }
            throw new PrepareException(503, ErrorCodes.MCP_INIT_FAILED, "MCP 初始化失败：" + descriptor.key());
        }
    }

    private void verifyBlocking(McpDescriptor descriptor, String secret) {
        McpSyncClient client = createClient(descriptor, secret);
        try {
            client.initialize();
            List<McpSchema.Tool> tools = listAll(client);
            if (tools.isEmpty()) {
                throw new PrepareException(422, ErrorCodes.MCP_INIT_FAILED, "MCP 没有可用工具：" + descriptor.key());
            }
            if (descriptor.tools().isEmpty()) {
                throw new PrepareException(422, ErrorCodes.MCP_INIT_FAILED, "MCP 发布目录为空：" + descriptor.key());
            }
            Map<String, McpSchema.Tool> actual = new java.util.LinkedHashMap<>();
            tools.forEach(tool -> actual.put(tool.name(), tool));
            for (McpToolDescriptor frozen : descriptor.tools()) {
                McpSchema.Tool tool = actual.get(frozen.name());
                if (tool == null) {
                    throw new PrepareException(
                            422, ErrorCodes.SCHEMA_DRIFT, "MCP 工具已不存在：" + descriptor.key() + "." + frozen.name());
                }
                if (!McpToolSchemas.matchesFrozen(McpSchemaMaps.inputSchema(tool), frozen)) {
                    throw new PrepareException(
                            422,
                            ErrorCodes.SCHEMA_DRIFT,
                            "MCP 工具 Schema 已漂移：" + descriptor.key() + "." + frozen.name());
                }
            }
            String catalogDigest = ComponentDigests.mcpCatalog(descriptor.tools());
            if (descriptor.catalogDigest() != null
                    && !descriptor.catalogDigest().isBlank()
                    && !catalogDigest.equals(descriptor.catalogDigest())) {
                throw new PrepareException(422, ErrorCodes.SCHEMA_DRIFT, "MCP 工具目录摘要不一致：" + descriptor.key());
            }
        } catch (PrepareException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PrepareException(503, ErrorCodes.MCP_INIT_FAILED, "MCP 初始化失败：" + descriptor.key());
        } finally {
            closeQuietly(client);
        }
    }

    public McpConnection acquire(McpDescriptor descriptor, String secret) {
        String key = key(descriptor);
        McpConnection existing = connections.get(key);
        if (usable(existing)) {
            existing.acquire();
            return existing;
        }
        CompletableFuture<McpConnection> future = creating.computeIfAbsent(
                key, ignored -> CompletableFuture.supplyAsync(() -> connect(descriptor, secret), mcpExecutor));
        try {
            McpConnection connection = future.get(properties.mcp().initTimeoutMs() + 5_000L, TimeUnit.MILLISECONDS);
            connection.acquire();
            return connection;
        } catch (TimeoutException exception) {
            throw new PrepareException(503, ErrorCodes.MCP_INIT_FAILED, "MCP 初始化超时：" + descriptor.key());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PrepareException(503, ErrorCodes.MCP_INIT_FAILED, "MCP 初始化被中断");
        } catch (java.util.concurrent.ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof PrepareException prepareException) {
                throw prepareException;
            }
            throw new PrepareException(503, ErrorCodes.MCP_INIT_FAILED, "MCP 初始化失败：" + descriptor.key());
        } finally {
            creating.remove(key, future);
        }
    }

    public McpToolEnvelope call(McpConnection connection, String originalToolName, Map<String, Object> arguments) {
        var requestAttributes = io.opentelemetry.api.common.Attributes.builder()
                .put("gen_ai.tool.name", originalToolName)
                .put("mcp.component.key", connection.descriptor().key());
        if (traceContentEnabled()) {
            requestAttributes.put(
                    "gen_ai.tool.call.arguments",
                    SensitiveDataSanitizer.sanitize(
                            render(argumentsForTrace(arguments), "{}"), traceMaxContentChars()));
        }
        io.opentelemetry.api.trace.Span.current().addEvent("mcp.request", requestAttributes.build());
        long started = System.nanoTime();
        Future<McpSchema.CallToolResult> future = null;
        try {
            future = mcpExecutor.submit(
                    () -> connection.client().callTool(new McpSchema.CallToolRequest(originalToolName, arguments)));
            McpSchema.CallToolResult result = future.get(properties.mcp().callTimeoutMs(), TimeUnit.MILLISECONDS);
            var responseAttributes = io.opentelemetry.api.common.Attributes.builder()
                    .put("gen_ai.tool.name", originalToolName)
                    .put("mcp.component.key", connection.descriptor().key());
            if (traceContentEnabled()) {
                responseAttributes.put(
                        "gen_ai.tool.call.result",
                        SensitiveDataSanitizer.sanitize(render(result, "{}"), traceMaxContentChars()));
            }
            io.opentelemetry.api.trace.Span.current().addEvent("mcp.response", responseAttributes.build());
            return envelope(connection.descriptor().key(), result);
        } catch (TimeoutException exception) {
            if (future != null) {
                // 只取消本地等待，不重放已经发出的远端调用；结果仍可能已在 Provider 侧发生。
                future.cancel(true);
            }
            metrics.counter("mcp_error_total", "mcp", connection.descriptor().key(), "code", "TIMEOUT")
                    .increment();
            return McpToolEnvelope.failure(
                    "MCP 调用超时，结果可能未返回", connection.descriptor().key());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return McpToolEnvelope.failure("MCP 调用被中断", connection.descriptor().key());
        } catch (java.util.concurrent.ExecutionException exception) {
            metrics.counter("mcp_error_total", "mcp", connection.descriptor().key(), "code", "FAILED")
                    .increment();
            Throwable cause = exception.getCause();
            log.warn(
                    "mcp call failed: mcp={} tool={} reason={}",
                    connection.descriptor().key(),
                    originalToolName,
                    cause == null ? exception.getClass().getSimpleName() : cause.toString());
            return McpToolEnvelope.failure("MCP 调用失败", connection.descriptor().key());
        } finally {
            Timer.builder("mcp_call_duration")
                    .tag("mcp", connection.descriptor().key())
                    .register(metrics)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private boolean traceContentEnabled() {
        var observability = properties.observability();
        return observability != null
                && observability.otel() != null
                && observability.otel().contentEnabled();
    }

    private int traceMaxContentChars() {
        var observability = properties.observability();
        return observability == null || observability.otel() == null
                ? 0
                : observability.otel().maxContentChars();
    }

    private McpToolEnvelope envelope(String source, McpSchema.CallToolResult result) {
        List<String> warnings = new ArrayList<>();
        Object structuredContent = result.structuredContent();
        List<Map<String, Object>> content = new ArrayList<>();
        String text = "";
        if (result.content() != null) {
            for (McpSchema.Content item : result.content()) {
                if (item instanceof McpSchema.TextContent textContent) {
                    text = text.isEmpty() ? textContent.text() : text + "\n" + textContent.text();
                }
                try {
                    Map<String, Object> mapped = json.convertValue(item, new TypeReference<Map<String, Object>>() {});
                    if (mapped != null) {
                        content.add(mapped);
                    }
                } catch (RuntimeException exception) {
                    warnings.add("工具返回了无法序列化的 content 项");
                }
            }
        }
        Object data = structuredContent;
        if (data == null && !text.isBlank()) {
            try {
                data = json.readValue(text, new TypeReference<Map<String, Object>>() {});
            } catch (Exception exception) {
                data = text;
            }
        }
        if (data instanceof Map<?, ?> map && map.get("warnings") instanceof List<?> list) {
            list.forEach(item -> warnings.add(String.valueOf(item)));
        }
        boolean error = Boolean.TRUE.equals(result.isError());
        String rendered = render(
                Map.of(
                        "data", data == null ? Map.of() : data,
                        "structuredContent", structuredContent == null ? Map.of() : structuredContent,
                        "content", content),
                text);
        boolean truncated = rendered.length() > properties.mcp().maxResultChars();
        if (truncated) {
            data = Map.of(
                    "truncated", true,
                    "chars", rendered.length(),
                    "preview", rendered.substring(0, properties.mcp().maxResultChars()));
            structuredContent = Map.of();
            content = List.of();
            warnings.add("工具结果过长，已截断");
        }
        return new McpToolEnvelope(
                !error,
                data == null ? Map.of() : data,
                structuredContent,
                content,
                error ? "MCP 工具返回错误" : null,
                warnings,
                source,
                truncated,
                error);
    }

    private String render(Object data, String fallback) {
        try {
            return data == null ? fallback : json.writeValueAsString(data);
        } catch (Exception exception) {
            return String.valueOf(data);
        }
    }

    private Map<String, Object> argumentsForTrace(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> traceArguments = new LinkedHashMap<>();
        arguments.forEach((name, value) -> {
            if (!OpsMcpInvocationAdapter.CONTEXT_FIELD.equalsIgnoreCase(name)) {
                traceArguments.put(name, value);
            }
        });
        return traceArguments;
    }

    private McpConnection connect(McpDescriptor descriptor, String secret) {
        if (!reserveConnectionSlot()) {
            throw new PrepareException(429, ErrorCodes.RUN_BUSY, "MCP 连接数已达上限");
        }
        McpSyncClient client = null;
        try {
            client = createClient(descriptor, secret);
            client.initialize();
            McpConnection connection = new McpConnection(key(descriptor), descriptor, client);
            McpConnection previous = connections.putIfAbsent(key(descriptor), connection);
            if (previous != null) {
                closeQuietly(client);
                releaseConnectionSlot();
                return previous;
            }
            return connection;
        } catch (RuntimeException exception) {
            if (client != null) {
                closeQuietly(client);
            }
            releaseConnectionSlot();
            throw new PrepareException(503, ErrorCodes.MCP_INIT_FAILED, "MCP 初始化失败：" + descriptor.key());
        }
    }

    private boolean reserveConnectionSlot() {
        int limit = properties.mcp().maxConnections();
        while (true) {
            int current = connectionCount.get();
            if (current >= limit) {
                return false;
            }
            if (connectionCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void releaseConnectionSlot() {
        connectionCount.updateAndGet(current -> Math.max(0, current - 1));
    }

    private McpSyncClient createClient(McpDescriptor descriptor, String secret) {
        ComponentEndpoint endpoint;
        try {
            endpoint = ComponentEndpoint.parse(EndpointPolicy.requireAllowedAndResolvable(descriptor.endpoint())
                    .toString());
        } catch (IllegalArgumentException exception) {
            throw new PrepareException(422, ErrorCodes.MCP_INIT_FAILED, "MCP 入口地址不安全：" + descriptor.key());
        }
        String path = endpoint.path().isBlank() ? "/mcp" : endpoint.path();
        if (!endpoint.query().isBlank()) {
            path = path + "?" + endpoint.query();
        }
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(endpoint.baseUrl())
                .endpoint(path)
                .connectTimeout(Duration.ofMillis(properties.mcp().connectTimeoutMs()))
                .httpRequestCustomizer((request, method, uri, body, context) -> {
                    if (!OpsMcpInvocationAdapter.applies(descriptor) && secret != null && !secret.isBlank()) {
                        request.header("Authorization", "Bearer " + secret);
                    }
                })
                .build();
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(properties.mcp().callTimeoutMs()))
                .initializationTimeout(Duration.ofMillis(properties.mcp().initTimeoutMs()))
                .clientInfo(new McpSchema.Implementation("ipe-agent-runtime", "1.0.0"))
                .build();
    }

    private List<McpSchema.Tool> listAll(McpSyncClient client) {
        List<McpSchema.Tool> tools = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            McpSchema.ListToolsResult result = cursor == null ? client.listTools() : client.listTools(cursor);
            if (result.tools() != null) {
                tools.addAll(result.tools());
            }
            if (tools.size() > MAX_TOOLS) {
                throw new PrepareException(422, ErrorCodes.MCP_INIT_FAILED, "MCP 工具数量超过上限");
            }
            cursor = result.nextCursor();
            if (cursor == null || cursor.isBlank()) {
                return tools;
            }
            if (!cursors.add(cursor)) {
                throw new PrepareException(422, ErrorCodes.MCP_INIT_FAILED, "MCP 分页游标重复");
            }
        }
        throw new PrepareException(422, ErrorCodes.MCP_INIT_FAILED, "MCP 工具分页超过上限");
    }

    private boolean usable(McpConnection connection) {
        return connection != null && !connection.closed() && !connection.draining();
    }

    private String key(McpDescriptor descriptor) {
        return descriptor.key() + "|" + descriptor.version() + "|" + descriptor.endpoint() + "|"
                + descriptor.secretRevision();
    }

    private void cleanupIdle() {
        Instant threshold = Instant.now().minusSeconds(properties.mcp().idleSeconds());
        connections.forEach((key, connection) -> {
            if (connection.references() == 0
                    && connection.lastUsed().isBefore(threshold)
                    && connections.remove(key, connection)) {
                try {
                    connection.close();
                } finally {
                    releaseConnectionSlot();
                }
            }
        });
    }

    private static void closeQuietly(McpSyncClient client) {
        try {
            client.closeGracefully();
        } catch (RuntimeException exception) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                log.debug("mcp client close failed");
            }
        }
    }

    @PreDestroy
    void close() {
        cleaner.shutdownNow();
        connections.values().forEach(McpConnection::close);
        connections.clear();
        connectionCount.set(0);
    }
}
