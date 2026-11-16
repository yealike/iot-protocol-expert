package top.yealike.ipe.agent.runtime.mcp;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import top.yealike.ipe.agent.runtime.control.ControlClient;
import top.yealike.ipe.agent.runtime.control.ControlException;
import top.yealike.ipe.agent.runtime.execution.RunGuard;
import top.yealike.ipe.agent.runtime.observability.RuntimeObservability;
import top.yealike.ipe.agent.runtime.prepare.PreparedTool;
import top.yealike.ipe.ai.contract.CredentialResolveRequest;
import top.yealike.ipe.ai.contract.CredentialResolveResponse;
import top.yealike.ipe.ai.contract.Digests;
import top.yealike.ipe.ai.contract.ErrorCodes;
import top.yealike.ipe.ai.contract.McpDescriptor;
import top.yealike.ipe.ai.contract.ToolAuthorizeRequest;
import top.yealike.ipe.ai.contract.ToolAuthorizeResponse;

/** 动态 MCP 工具：模型只看到 alias 与冻结 Schema，实际调用前每次向 Control 申请授权。 */
public final class McpAgentTool implements AgentTool {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpAgentTool.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final PreparedTool tool;
    private final McpDescriptor descriptor;
    private final RunGuard guard;
    private final ControlClient control;
    private final McpConnectionPool pool;
    private final MeterRegistry metrics;
    private final RuntimeObservability observability;

    public McpAgentTool(
            PreparedTool tool,
            McpDescriptor descriptor,
            RunGuard guard,
            ControlClient control,
            McpConnectionPool pool,
            MeterRegistry metrics,
            RuntimeObservability observability) {
        this.tool = tool;
        this.descriptor = descriptor;
        this.guard = guard;
        this.control = control;
        this.pool = pool;
        this.metrics = metrics;
        this.observability = observability;
    }

    @Override
    public String getName() {
        return tool.alias();
    }

    @Override
    public String getDescription() {
        return tool.descriptor().description() == null ? "" : tool.descriptor().description();
    }

    @Override
    public Map<String, Object> getParameters() {
        return tool.parametersForModel();
    }

    @Override
    public boolean isReadOnly() {
        return tool.descriptor().readOnly();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.<ToolResultBlock>fromCallable(() -> {
                    org.slf4j.MDC.put("tool_alias", getName());
                    try {
                        return observability.observeSupplier("mcp " + getName(), () -> invoke(param.getInput()));
                    } finally {
                        org.slf4j.MDC.remove("tool_alias");
                    }
                })
                // SDK 调用由 McpConnectionPool 投递到专用 ai-mcp- 有界线程池；这里不能再占用同一线程池，
                // 否则 4 个并发工具会把线程全部用于等待自己投递的 callTool 任务，形成自锁。
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock invoke(Map<String, Object> input) {
        try {
            guard.ensureActive();
        } catch (RunGuard.RunAbortedException exception) {
            return failure(exception.code(), exception.getMessage(), "run");
        }
        if (input != null && input.containsKey(OpsMcpInvocationAdapter.CONTEXT_FIELD)) {
            return failure(ErrorCodes.TOOL_DENIED, "模型不得提交保留字段 _ipe_context", descriptor.key());
        }
        String requestId = UUID.randomUUID().toString();
        String actor = null;
        String stationScope = null;
        boolean slot = false;
        McpConnection connection = null;
        try {
            guard.reserveToolCall();
            guard.acquireToolSlot();
            slot = true;
            ToolAuthorizeResponse authorization;
            try {
                authorization = control.authorize(new ToolAuthorizeRequest(
                        guard.runId(),
                        guard.executionToken(),
                        tool.alias(),
                        Digests.digest(input == null ? Map.of() : input),
                        guard.authVersion(),
                        requestId));
            } catch (ControlException exception) {
                return failure(ErrorCodes.TOOL_DENIED, "工具授权校验失败", descriptor.key());
            }
            if (authorization == null || !authorization.allowed()) {
                metrics.counter("mcp_error_total", "mcp", descriptor.key(), "code", "DENIED")
                        .increment();
                return failure(
                        ErrorCodes.TOOL_DENIED,
                        authorization == null || authorization.reason() == null
                                ? "当前账号无权调用该工具"
                                : authorization.reason(),
                        descriptor.key());
            }
            actor = authorization.actorId();
            stationScope = authorization.stationScope();
            String secret = null;
            if (descriptor.credentialRef() != null
                    && !descriptor.credentialRef().isBlank()) {
                try {
                    CredentialResolveResponse credential = control.resolveCredential(new CredentialResolveRequest(
                            guard.runId(),
                            guard.executionToken(),
                            descriptor.credentialRef(),
                            descriptor.secretRevision(),
                            "RUN",
                            null));
                    secret = credential == null ? null : credential.secret();
                } catch (ControlException exception) {
                    return failure(ErrorCodes.CREDENTIAL_UNAVAILABLE, "工具凭据不可用", descriptor.key());
                }
            }
            connection = pool.acquire(descriptor, secret);
            Map<String, Object> arguments = new LinkedHashMap<>(input == null ? Map.of() : input);
            if (OpsMcpInvocationAdapter.applies(descriptor)) {
                if (secret == null || secret.isBlank()) {
                    log.warn(
                            "mcp tool credential unavailable: mcp={} alias={} credentialRef={}",
                            descriptor.key(),
                            getName(),
                            descriptor.credentialRef());
                    return failure(ErrorCodes.CREDENTIAL_UNAVAILABLE, "OPS 调用上下文密钥不可用", descriptor.key());
                }
                arguments.put(
                        OpsMcpInvocationAdapter.CONTEXT_FIELD,
                        OpsMcpInvocationAdapter.seal(tool.originalName(), actor, stationScope, secret));
            }
            McpToolEnvelope envelope = pool.call(connection, tool.originalName(), arguments);
            String json = render(envelope);
            return envelope.ok() ? ToolResultBlock.text(json) : ToolResultBlock.error(json);
        } catch (RunGuard.RunAbortedException exception) {
            return failure(exception.code(), exception.getMessage(), descriptor.key());
        } catch (RuntimeException exception) {
            metrics.counter("mcp_error_total", "mcp", descriptor.key(), "code", "UNAVAILABLE")
                    .increment();
            log.warn(
                    "mcp tool invocation failed: mcp={} alias={} reason={}",
                    descriptor.key(),
                    getName(),
                    safeMessage(exception),
                    exception);
            return failure(ErrorCodes.MCP_TOOL_ERROR, "MCP 工具暂不可用", descriptor.key());
        } finally {
            if (slot) {
                guard.releaseToolSlot();
            }
            if (connection != null) {
                connection.release();
            }
        }
    }

    private ToolResultBlock failure(String code, String message, String source) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("data", Map.of());
        body.put("error", error);
        body.put("warnings", java.util.List.of(message));
        body.put("source", source);
        return ToolResultBlock.error(render(body));
    }

    private String render(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception exception) {
            return "{\"ok\":false,\"error\":{\"code\":\"MCP_TOOL_ERROR\"}}";
        }
    }

    private String render(McpToolEnvelope envelope) {
        return render(envelope.asMap());
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
