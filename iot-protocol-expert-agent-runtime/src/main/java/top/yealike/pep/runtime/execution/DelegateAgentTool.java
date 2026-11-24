package top.yealike.pep.runtime.execution;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.opentelemetry.context.Context;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;
import top.yealike.pep.runtime.api.RunRejectedException;
import top.yealike.pep.runtime.contract.DelegateBinding;
import top.yealike.pep.runtime.contract.DelegateOutput;
import top.yealike.pep.runtime.contract.DelegateTaskRequest;
import top.yealike.pep.runtime.contract.DelegateTaskResponse;
import top.yealike.pep.runtime.contract.DelegationPolicy;
import top.yealike.pep.runtime.contract.Digests;
import top.yealike.pep.runtime.control.ControlException;
import top.yealike.pep.runtime.prepare.domain.PreparedRelease;

/** 模型可见的同步委派工具，只展示当前 Release 的别名。 */
final class DelegateAgentTool implements AgentTool {

    private static final Logger LOG = LoggerFactory.getLogger(DelegateAgentTool.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PreparedRelease release;
    private final RunGuard guard;
    private final String instanceId;
    private final DelegateTransport transport;

    DelegateAgentTool(PreparedRelease release, RunGuard guard, String instanceId, DelegateTransport transport) {
        this.release = release;
        this.guard = guard;
        this.instanceId = instanceId;
        this.transport = transport;
    }

    @Override
    public String getName() {
        return DelegationPolicy.TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "把自包含的子任务交给已发布的领域 Agent。子 Agent 看不到父会话历史。\n"
                + release.manifest().delegates().stream()
                        .map(binding -> binding.alias() + "：" + binding.description()
                                + (binding.whenToUse() == null ? "" : " " + binding.whenToUse()))
                        .collect(Collectors.joining("\n"));
    }

    @Override
    public Map<String, Object> getParameters() {
        List<String> aliases = release.manifest().delegates().stream()
                .map(DelegateBinding::alias)
                .toList();
        return Map.of(
                "type",
                "object",
                "required",
                List.of("target", "task"),
                "additionalProperties",
                false,
                "properties",
                Map.of(
                        "target", Map.of("type", "string", "enum", aliases),
                        "task", Map.of("type", "string", "maxLength", 4000, "description", "自包含任务说明"),
                        "context_refs", Map.of("type", "array", "maxItems", 0, "items", Map.of("type", "string")),
                        "expected_output",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "format",
                                                        Map.of(
                                                                "type",
                                                                "string",
                                                                "enum",
                                                                List.of("text", "markdown", "json")),
                                                "max_chars", Map.of("type", "integer", "minimum", 1, "maximum", 8000))),
                        "deadline_seconds", Map.of("type", "integer", "minimum", 1, "maximum", 300),
                        "idempotency_key", Map.of("type", "string", "maxLength", 128)));
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.create(sink -> {
            try {
                guard.reserveToolCall();
            } catch (RunGuard.RunAbortedException exception) {
                sink.success(error(exception.code(), exception.getMessage()));
                return;
            }
            if (!guard.tryEnterDelegation()) {
                sink.success(error("DELEGATION_CONCURRENCY_LIMIT", "父 Run 已有活跃委派"));
                return;
            }
            Thread thread = Thread.ofVirtual()
                    .name("ai-delegate-" + guard.runId())
                    .unstarted(Context.current().wrap(() -> {
                        ToolResultBlock result;
                        try {
                            result = invoke(param);
                        } finally {
                            guard.leaveDelegation();
                        }
                        sink.success(result);
                    }));
            sink.onCancel(thread::interrupt);
            Integer waitSeconds = integer(param.getInput().get("deadline_seconds"));
            if (waitSeconds != null && waitSeconds > 0 && waitSeconds <= DelegationPolicy.HARD_MAX_TIME_MS / 1000) {
                sink.onDispose(Mono.delay(Duration.ofSeconds(waitSeconds)).subscribe(ignored -> {
                    sink.success(error("DELEGATE_WAIT_TIMEOUT", "父任务已停止等待，子任务可能仍在运行"));
                }));
            }
            thread.start();
        });
    }

    private ToolResultBlock invoke(ToolCallParam param) {
        try {
            guard.ensureActive();
            Map<String, Object> arguments = param.getInput();
            String alias = string(arguments.get("target"));
            if (release.manifest().delegates().stream()
                    .noneMatch(binding -> binding.alias().equals(alias))) {
                return error("DELEGATE_NOT_ALLOWED", "目标不在发布白名单内");
            }
            if (arguments.get("context_refs") instanceof Collection<?> refs && !refs.isEmpty()) {
                return error("CONTEXT_REFS_UNSUPPORTED", "请在任务中填写必要上下文");
            }
            String key = string(arguments.get("idempotency_key"));
            if (key == null || key.isBlank()) {
                key = Digests.digest(arguments);
            }
            DelegateOutput output = arguments.get("expected_output") instanceof Map<?, ?> fields
                    ? new DelegateOutput(string(fields.get("format")), integer(fields.get("max_chars")))
                    : null;
            DelegateTaskResponse result = transport.invoke(new DelegateTaskRequest(
                    guard.runId(),
                    guard.executionToken(),
                    instanceId,
                    release.releaseId(),
                    alias,
                    key,
                    string(arguments.get("task")),
                    List.of(),
                    output,
                    integer(arguments.get("deadline_seconds"))));
            return format(result);
        } catch (RunGuard.RunAbortedException exception) {
            return error(exception.code(), exception.getMessage());
        } catch (RunRejectedException exception) {
            LOG.warn("本地子 Run 未启动 parentRunId={} code={}", guard.runId(), exception.code());
            return error("CHILD_RUN_FAILED", "无法启动本地子任务");
        } catch (ControlException exception) {
            return error(exception.code(), exception.status() < 500 ? exception.getMessage() : "委派服务暂不可用");
        } catch (RuntimeException exception) {
            LOG.warn("委派工具执行失败 parentRunId={}", guard.runId(), exception);
            return error("CHILD_RUN_FAILED", "子任务执行失败");
        }
    }

    private static ToolResultBlock format(DelegateTaskResponse result) {
        if (result == null) {
            return error("CONTROL_RESPONSE_INVALID", "控制面未返回任务结果");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", result.taskId().toString());
        data.put("childRunId", result.child().runId().toString());
        data.put("state", result.state());
        data.put("usage", result.usage());
        String code = result.errorCode();
        if ("COMPLETED".equals(result.state())) {
            data.put("untrustedResult", result.resultSummary());
            data.put("truncated", result.truncated());
            if (result.truncated()) {
                data.put("notice", "结果已截断，完整内容在子任务会话");
            }
        } else {
            code = code == null ? "DELEGATE_WAIT_TIMEOUT" : code;
            data.put("errorCode", code);
            data.put("retryable", false);
            data.put("message", result.errorMessage() == null ? "子任务尚未终结" : result.errorMessage());
        }
        return block(data, code);
    }

    private static ToolResultBlock error(String code, String message) {
        return block(
                Map.of(
                        "errorCode",
                        code,
                        "retryable",
                        "DELEGATION_CONCURRENCY_LIMIT".equals(code),
                        "message",
                        message == null ? "委派执行失败" : message),
                code);
    }

    private static ToolResultBlock block(Map<String, Object> data, String code) {
        return ToolResultBlock.builder()
                .state(code == null ? ToolResultState.SUCCESS : ToolResultState.ERROR)
                .output(TextBlock.builder().text(JSON.writeValueAsString(data)).build())
                .metadata(
                        code == null
                                ? Map.of(ToolAuditMetadata.DECISION, ToolAuditMetadata.ALLOW)
                                : Map.of(ToolAuditMetadata.ERROR_CODE, code))
                .build();
    }

    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }

    private static Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new java.math.BigDecimal(value.toString()).intValueExact();
        } catch (IllegalArgumentException | ArithmeticException exception) {
            return -1;
        }
    }
}
