package top.yealike.ipe.ai.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import top.yealike.ipe.ai.gateway.McpGateway;
import top.yealike.ipe.ai.support.JsonSupport;

@Component
public class RemoteTools {
    private static final Set<String> MODEL_TOOLS = Set.of(
            ToolNames.GET_ORDER_DETAIL,
            ToolNames.GET_ORDER_BILLS,
            ToolNames.GET_DEVICE_DETAIL,
            ToolNames.GET_ORDER_TELEMETRY_SUMMARY);
    private final ToolGuard guard;
    private final McpGateway gateway;

    public RemoteTools(ToolGuard guard, McpGateway gateway) {
        this.guard = guard;
        this.gateway = gateway;
    }

    public void register(Toolkit toolkit, ToolSession session, Collection<String> allowed) {
        if (allowed.stream().noneMatch(MODEL_TOOLS::contains)) return;
        try {
            for (Object item : gateway.tools()) {
                var schema = McpGateway.object(item);
                if (!(schema.get("name") instanceof String name)
                        || !MODEL_TOOLS.contains(name)
                        || !allowed.contains(name)) continue;
                var input = new java.util.LinkedHashMap<>(McpGateway.object(schema.get("inputSchema")));
                var fields = new java.util.LinkedHashMap<>(McpGateway.object(input.get("properties")));
                fields.remove("_ipe_context");
                input.put("properties", fields);
                if (input.get("required") instanceof List<?> required) {
                    input.put(
                            "required",
                            required.stream()
                                    .filter(key -> !"_ipe_context".equals(key))
                                    .toList());
                }
                toolkit.registration()
                        .agentTool(new RemoteTool(session, name, String.valueOf(schema.get("description")), input))
                        .apply();
            }
        } catch (RuntimeException ex) {
            session.addWarning("MCP 工具目录暂不可用");
        }
    }

    public String invoke(ToolSession session, String name, Map<String, Object> args) {
        if (!MODEL_TOOLS.contains(name)) return error("未知工具");
        ToolGuard.Denied denied = guard.check(session, name);
        if (denied != null) {
            guard.recordDenied(session, name, args, denied.reason());
            return error(denied.reason());
        }
        var callId = guard.start(session, name, args);
        long started = System.nanoTime();
        Map<String, Object> result;
        try {
            result = gateway.call(
                    name,
                    args,
                    Map.of("X-Ai-Actor", session.identity().actor(), "X-Ai-Station-Scope", session.stationScope()));
        } catch (RuntimeException ex) {
            String message = ex instanceof McpGateway.ToolFailure failure
                    ? switch (failure.status()) {
                        case 403 -> "无权访问该订单或设备所属站点";
                        case 404 -> "未找到对应数据";
                        default -> "业务 MCP 服务暂不可用";
                    }
                    : "业务 MCP 服务暂不可用";
            guard.finish(callId, "FAILED", Map.of("error", "MCP_ERROR"), started);
            session.addWarning(message);
            return error(message);
        }
        var warnings = result.get("warnings") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.<String>of();
        boolean partial = Boolean.TRUE.equals(result.get("partial"));
        if (partial) warnings.forEach(session::addWarning);
        guard.finish(callId, "SUCCEEDED", ToolGuard.summarize(result.get("data"), partial, warnings), started);
        return JsonSupport.write(result);
    }

    private static String error(String message) {
        return JsonSupport.write(Map.of(
                "data", Map.of(), "source", "OPS", "partial", true, "error", message, "warnings", List.of(message)));
    }

    private record Descriptor(String name, String description, Map<String, Object> parameters) {}

    private final class RemoteTool implements AgentTool {
        private final ToolSession session;
        private final Descriptor descriptor;

        private RemoteTool(ToolSession session, String name, String description, Map<String, Object> parameters) {
            this.session = session;
            descriptor = new Descriptor(name, description, parameters);
        }

        public String getName() {
            return descriptor.name();
        }

        public String getDescription() {
            return descriptor.description();
        }

        public Map<String, Object> getParameters() {
            return descriptor.parameters();
        }

        public boolean isReadOnly() {
            return true;
        }

        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> ToolResultBlock.text(invoke(session, getName(), param.getInput())))
                    .subscribeOn(Schedulers.boundedElastic());
        }
    }
}
