package top.yealike.ipe.ai.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import top.yealike.ipe.ai.support.JsonSupport;

/** Leader 的成员分派工具：子 Agent 继承同一用户、Run 与权限上下文。 */
public class DelegationTools {

    public interface Delegate {
        String ask(String agentKey, String question, ToolSession session);
    }

    private final ToolSession session;
    private final ToolGuard guard;
    private final Delegate delegate;

    public DelegationTools(ToolSession session, ToolGuard guard, Delegate delegate) {
        this.session = session;
        this.guard = guard;
        this.delegate = delegate;
    }

    @Tool(name = ToolNames.ASK_PROTOCOL_AGENT, description = "把协议定义、命令字、字段含义的问题交给协议知识助手，返回其结论与引用", readOnly = true)
    public String askProtocolAgent(
            @ToolParam(name = "question", required = true, description = "要交给协议知识助手的问题") String question) {
        return ask("protocol-expert", ToolNames.ASK_PROTOCOL_AGENT, question);
    }

    @Tool(name = ToolNames.ASK_ORDER_AGENT, description = "把订单、账单、设备、遥测的问题交给订单客服助手，返回其结论与来源", readOnly = true)
    public String askOrderAgent(
            @ToolParam(name = "question", required = true, description = "要交给订单客服助手的问题") String question) {
        return ask("order-service", ToolNames.ASK_ORDER_AGENT, question);
    }

    private String ask(String agentKey, String toolName, String question) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("agent", agentKey);
        args.put("question_length", question == null ? 0 : question.length());
        ToolGuard.Denied denied = guard.check(session, toolName);
        if (denied != null) {
            guard.recordDenied(session, toolName, args, denied.reason());
            return JsonSupport.write(error(denied.reason()));
        }
        UUID callId = guard.start(session, toolName, args);
        long started = System.nanoTime();
        try {
            String answer = delegate.ask(agentKey, question, session);
            guard.finish(callId, "SUCCEEDED", Map.of("agent", agentKey, "length", answer.length()), started);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("data", Map.of("agent", agentKey, "answer", answer));
            value.put("data_time", Instant.now().toString());
            value.put("source", agentKey);
            value.put("partial", false);
            value.put("warnings", List.of());
            return JsonSupport.write(value);
        } catch (RuntimeException exception) {
            guard.finish(callId, "FAILED", Map.of("error", "SUB_AGENT_FAILED"), started);
            return JsonSupport.write(error("成员执行失败：" + safeMessage(exception)));
        }
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("data", Map.of());
        value.put("data_time", Instant.now().toString());
        value.put("source", "TEAM");
        value.put("partial", true);
        value.put("warnings", List.of(message));
        value.put("error", message);
        return value;
    }
}
