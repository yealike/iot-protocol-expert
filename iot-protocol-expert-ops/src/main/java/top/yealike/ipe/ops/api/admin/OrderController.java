package top.yealike.ipe.ops.api.admin;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import top.yealike.ipe.ops.domain.ChargeOrder;
import top.yealike.ipe.ops.service.BillService;
import top.yealike.ipe.ops.service.OrderService;

@RestController
@RequestMapping("/api/ops")
public class OrderController {

    private final OrderService orders;
    private final BillService bills;

    public OrderController(OrderService orders, BillService bills) {
        this.orders = orders;
        this.bills = bills;
    }

    @GetMapping("/orders")
    public java.util.List<top.yealike.ipe.ops.dto.OpsDtos.OrderView> orders(
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

    @GetMapping("/orders/{orderNo}")
    public Map<String, Object> detail(@PathVariable String orderNo) {
        ChargeOrder order = orders.require(orderNo);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("order", orders.view(order));
        detail.put("bills", bills.byOrder(order.id).stream().map(bills::view).toList());
        return detail;
    }

    @GetMapping("/bills")
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
