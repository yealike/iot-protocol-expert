package top.yealike.ipe.ai.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import top.yealike.ipe.ai.gateway.OpsClient;
import top.yealike.ipe.ai.support.JsonSupport;

/**
 * 订单、账单、设备与遥测摘要工具。
 *
 * <p>所有数据来自 ops 内部只读接口，业务侧会再次校验站点范围；缺失字段用 warnings 明示，不由模型补齐。
 */
public class OrderTools {

    private final ToolSession session;
    private final ToolGuard guard;
    private final OpsClient ops;

    public OrderTools(ToolSession session, ToolGuard guard, OpsClient ops) {
        this.session = session;
        this.guard = guard;
        this.ops = ops;
    }

    @Tool(name = ToolNames.GET_ORDER_DETAIL, description = "按订单号查询订单详情，含状态、时间、设备与金额口径", readOnly = true)
    public String getOrderDetail(@ToolParam(name = "order_no", required = true, description = "平台订单号") String orderNo) {
        return invoke(
                ToolNames.GET_ORDER_DETAIL,
                Map.of("order_no", orderNo),
                () -> ops.orderDetail(session.identity(), session.stationScope(), orderNo));
    }

    @Tool(name = ToolNames.GET_ORDER_BILLS, description = "按订单号查询账单与计费结果，含来源与是否被接受", readOnly = true)
    public String getOrderBills(@ToolParam(name = "order_no", required = true, description = "平台订单号") String orderNo) {
        return invoke(
                ToolNames.GET_ORDER_BILLS,
                Map.of("order_no", orderNo),
                () -> ops.orderBills(session.identity(), session.stationScope(), orderNo));
    }

    @Tool(name = ToolNames.GET_DEVICE_DETAIL, description = "按设备 ID 查询设备档案、产品、协议与站点", readOnly = true)
    public String getDeviceDetail(
            @ToolParam(name = "device_id", required = true, description = "平台设备 ID") Long deviceId) {
        if (deviceId == null || deviceId <= 0) {
            return JsonSupport.write(error("device_id 必须是正整数"));
        }
        return invoke(
                ToolNames.GET_DEVICE_DETAIL,
                Map.of("device_id", deviceId),
                () -> ops.deviceDetail(session.identity(), session.stationScope(), deviceId));
    }

    @Tool(
            name = ToolNames.GET_ORDER_TELEMETRY_SUMMARY,
            description = "按订单号统计遥测摘要：首末有效 SOC 采样值、采样时间、点数与缺失情况",
            readOnly = true)
    public String getOrderTelemetrySummary(
            @ToolParam(name = "order_no", required = true, description = "平台订单号") String orderNo,
            @ToolParam(name = "window_hours", required = false, description = "统计窗口小时数，默认 24") Integer windowHours) {
        return invoke(
                ToolNames.GET_ORDER_TELEMETRY_SUMMARY,
                Map.of("order_no", orderNo, "window_hours", windowHours == null ? 0 : windowHours),
                () -> ops.telemetrySummary(session.identity(), session.stationScope(), orderNo, windowHours));
    }

    private String invoke(
            String toolName, Map<String, Object> args, java.util.function.Supplier<OpsClient.Envelope> supplier) {
        ToolGuard.Denied denied = guard.check(session, toolName);
        if (denied != null) {
            guard.recordDenied(session, toolName, args, denied.reason());
            return JsonSupport.write(error(denied.reason()));
        }
        UUID callId = guard.start(session, toolName, args);
        long started = System.nanoTime();
        OpsClient.Envelope envelope;
        try {
            envelope = supplier.get();
        } catch (RuntimeException exception) {
            guard.finish(callId, "FAILED", Map.of("error", "UPSTREAM_ERROR"), started);
            return JsonSupport.write(error("业务服务暂时不可用，未能获取数据"));
        }
        boolean failed = envelope.data().isEmpty() && !envelope.warnings().isEmpty();
        guard.finish(
                callId,
                failed ? "FAILED" : "SUCCEEDED",
                ToolGuard.summarize(envelope.data(), envelope.partial(), envelope.warnings()),
                started);
        if (envelope.partial()) {
            envelope.warnings().forEach(session::addWarning);
        }
        return JsonSupport.write(envelope.toMap());
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("data", Map.of());
        value.put("data_time", Instant.now().toString());
        value.put("source", "OPS");
        value.put("partial", true);
        value.put("warnings", List.of(message));
        value.put("error", message);
        return value;
    }
}
