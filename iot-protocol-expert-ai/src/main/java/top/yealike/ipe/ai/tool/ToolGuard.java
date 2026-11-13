package top.yealike.ipe.ai.tool;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.yealike.ipe.ai.policy.PolicyService;
import top.yealike.ipe.ai.run.RunRepository;

/**
 * 工具调用拦截：授权 → 预算 → 记录 → 结果摘要。
 *
 * <p>授权在工具执行前逐次校验，工具结果再次裁剪；工具结果属于不可信输入，不能反过来改变授权。
 */
@Service
public class ToolGuard {

    private static final Logger log = LoggerFactory.getLogger(ToolGuard.class);

    private final PolicyService policy;
    private final RunRepository runs;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    private final top.yealike.ipe.ai.identity.IdentityService identities;

    public ToolGuard(
            PolicyService policy,
            RunRepository runs,
            io.micrometer.core.instrument.MeterRegistry metrics,
            top.yealike.ipe.ai.identity.IdentityService identities) {
        this.policy = policy;
        this.runs = runs;
        this.metrics = metrics;
        this.identities = identities;
    }

    private void countCall(String toolName, String decision) {
        metrics.counter("ai_tool_calls_total", "tool", toolName, "decision", decision)
                .increment();
    }

    public record Denied(String reason) {}

    /** 返回 null 表示放行，否则返回拒绝原因。 */
    public Denied check(ToolSession session, String toolName) {
        try {
            var current = identities.resolveActor(session.identity().actor());
            if (current.authVersion() != session.identity().authVersion()
                    || !java.util.Set.copyOf(current.roles())
                            .equals(java.util.Set.copyOf(session.identity().roles()))
                    || !java.util.Set.copyOf(current.permissions())
                            .equals(java.util.Set.copyOf(session.identity().permissions()))) {
                return new Denied("账号权限已变化，请重新发起任务");
            }
        } catch (RuntimeException exception) {
            return new Denied("无法确认账号当前权限");
        }
        policy.reload();
        if (!policy.stationScopeHeader(session.identity()).equals(session.stationScope())) {
            return new Denied("站点范围已变化，请重新发起任务");
        }
        if (runs.isCancellationRequested(session.runId())) return new Denied("任务已请求停止");
        PolicyService.Decision decision = policy.canUseTool(session.identity(), session.agentKey(), toolName);
        if (!decision.allowed()) {
            log.info(
                    "tool denied: run={} agent={} tool={} policy={} reason={}",
                    session.runId(),
                    session.agentKey(),
                    toolName,
                    decision.policyVersion(),
                    decision.reason());
            return new Denied(decision.reason());
        }
        if (!session.tryAcquireCall()) {
            return new Denied("已达到本次运行的工具调用上限（" + session.maxToolCalls() + " 次）");
        }
        return null;
    }

    public UUID start(ToolSession session, String toolName, Map<String, Object> argsSummary) {
        countCall(toolName, "ALLOW");
        UUID id = UUID.randomUUID();
        runs.insertToolCall(
                id,
                session.runId(),
                session.conversationId(),
                toolName,
                "ALLOW",
                null,
                summarizeArguments(argsSummary),
                Instant.now());
        return id;
    }

    public void recordDenied(ToolSession session, String toolName, Map<String, Object> argsSummary, String reason) {
        countCall(toolName, "DENY");
        runs.insertToolCall(
                UUID.randomUUID(),
                session.runId(),
                session.conversationId(),
                toolName,
                "DENY",
                reason,
                summarizeArguments(argsSummary),
                Instant.now());
    }

    public void finish(UUID callId, String status, Map<String, Object> resultSummary, long startedAtNanos) {
        int durationMs = (int) ((System.nanoTime() - startedAtNanos) / 1_000_000);
        runs.finishToolCall(callId, status, resultSummary, durationMs);
    }

    private static Map<String, Object> summarizeArguments(Map<String, Object> args) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (args != null) {
            args.forEach((key, value) -> summary.put(key, value == null ? "null" : "provided"));
        }
        return summary;
    }

    /** 结果摘要只留结构与状态，不记录完整业务数据。 */
    public static Map<String, Object> summarize(Object data, boolean partial, List<String> warnings) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("partial", partial);
        if (warnings != null && !warnings.isEmpty()) {
            summary.put("warnings", warnings.size());
        }
        if (data instanceof Map<?, ?> map) {
            summary.put(
                    "keys", map.keySet().stream().map(String::valueOf).limit(8).toList());
        } else if (data instanceof List<?> list) {
            summary.put("items", list.size());
        }
        return summary;
    }
}
