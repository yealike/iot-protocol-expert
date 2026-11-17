package top.yealike.pep.control.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import top.yealike.pep.control.charging.domain.ChargeOrder;
import top.yealike.pep.control.api.dto.OpsDtos;
import top.yealike.pep.control.charging.service.BillService;
import top.yealike.pep.control.charging.service.OrderService;

@RestController
@RequestMapping("/api/ops")
@Tag(name = "订单与账单", description = "充电订单、订单时序数据和账单查询")
public class OrderController {

    private final top.yealike.pep.control.telemetry.OrderTelemetryService telemetry;
    private final OrderService orders;
    private final BillService bills;

    public OrderController(
            OrderService orders, BillService bills, top.yealike.pep.control.telemetry.OrderTelemetryService telemetry) {
        this.telemetry = telemetry;
        this.orders = orders;
        this.bills = bills;
    }

    @GetMapping("/orders")
    @Operation(summary = "查询充电订单")
    public java.util.List<top.yealike.pep.control.api.dto.OpsDtos.OrderView> orders(
            @RequestParam(required = false) Long stationId,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) String gunNo,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            @RequestParam(required = false) List<ChargeOrder.Status> status) {
        return orders.search(stationId, deviceId, gunNo, protocol, orderNo, from, to, status).stream()
                .map(orders::view)
                .toList();
    }

    @GetMapping("/orders/page")
    @Operation(summary = "分页查询充电订单")
    public top.yealike.pep.control.support.page.PageResult<OpsDtos.OrderView> orderPage(
            @RequestParam(required = false) Long stationId,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) String gunNo,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            @RequestParam(required = false) List<ChargeOrder.Status> status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = orders.searchPage(stationId, deviceId, gunNo, protocol, orderNo, from, to, status, page, size);
        return top.yealike.pep.control.support.page.PageResult.of(
                result.content(), result.page(), result.size(), result.total(), orders::view);
    }

    @GetMapping("/orders/{orderNo}")
    @Operation(summary = "查询订单详情和账单")
    public Map<String, Object> detail(@Parameter(description = "平台订单号") @PathVariable String orderNo) {
        ChargeOrder order = orders.require(orderNo);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("order", orders.view(order));
        detail.put("bills", bills.byOrder(order.id).stream().map(bills::view).toList());
        return detail;
    }

    @GetMapping("/orders/{orderNo}/telemetry")
    @Operation(summary = "查询订单遥测曲线")
    public top.yealike.pep.control.telemetry.OrderTelemetryService.Result telemetry(
            @Parameter(description = "平台订单号") @PathVariable String orderNo) {
        return telemetry.query(orderNo);
    }

    @GetMapping("/bills")
    @Operation(summary = "查询充电账单")
    public List<Object> bills(
            @RequestParam(required = false) Long stationId,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String sourceTransactionNo,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            @RequestParam(required = false) Boolean accepted) {
        return bills.search(stationId, deviceId, protocol, sourceTransactionNo, from, to, accepted).stream()
                .map(bills::view)
                .map(o -> (Object) o)
                .toList();
    }
}
