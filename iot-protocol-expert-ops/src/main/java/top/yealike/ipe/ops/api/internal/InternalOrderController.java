package top.yealike.ipe.ops.api.internal;

import org.springframework.web.bind.annotation.*;
import top.yealike.ipe.ops.device.entity.DeviceInstance;
import top.yealike.ipe.ops.domain.ChargeOrder;
import top.yealike.ipe.ops.dto.OpsDtos;
import top.yealike.ipe.ops.service.DeviceService;
import top.yealike.ipe.ops.service.OrderService;

@RestController
@RequestMapping("/internal/orders")
public class InternalOrderController {

    private final OrderService orders;
    private final DeviceService devices;

    public InternalOrderController(OrderService orders, DeviceService devices) {
        this.orders = orders;
        this.devices = devices;
    }

    /** 设备主动申请启动（云快充 0x31）的业务判定。 */
    @PostMapping("/start")
    public OpsDtos.StartResult start(@RequestBody OpsDtos.StartRequest request) {
        DeviceInstance device = devices.resolve(request.protocol(), request.protocolDeviceId());
        String gunNo = request.gunNo();
        if (gunNo == null
                || !gunNo.matches("[0-9]{2}")
                || Integer.parseInt(gunNo) < 1
                || Integer.parseInt(gunNo) > devices.gunCount(device.id)) {
            throw new IllegalArgumentException("枪号不属于设备");
        }
        if (!device.allowWork) {
            return new OpsDtos.StartResult(false, null, 0x05, "设备已停用");
        }
        if (orders.active(device.id, gunNo) != null) {
            return new OpsDtos.StartResult(false, null, 0x0A, "枪已在充电");
        }
        ChargeOrder order = orders.create(
                device.id,
                gunNo,
                request.startType(),
                request.account(),
                "00000000",
                device.protocol,
                "OCPP16J".equals(device.protocol));
        return new OpsDtos.StartResult(true, order.orderNo, 0x00, "启动通过");
    }

    @GetMapping("/active")
    public OpsDtos.OrderView active(
            @RequestParam String protocol, @RequestParam String protocolDeviceId, @RequestParam String gunNo) {
        DeviceInstance device = devices.resolve(protocol, protocolDeviceId);
        ChargeOrder order = orders.active(device.id, gunNo);
        return order == null ? null : orders.view(order);
    }

    @GetMapping("/by-no/{orderNo}")
    public OpsDtos.OrderView byNo(@PathVariable String orderNo) {
        ChargeOrder order = orders.findByOrderNo(orderNo);
        return order == null ? null : orders.view(order);
    }

    @GetMapping("/by-ocpp")
    public OpsDtos.OrderView byOcpp(
            @RequestParam String protocol, @RequestParam String protocolDeviceId, @RequestParam Integer transactionId) {
        devices.resolve(protocol, protocolDeviceId);
        ChargeOrder order = orders.byOcppTransactionId(transactionId);
        return order == null ? null : orders.view(order);
    }

    @PostMapping("/{orderNo}/bind-ocpp")
    public OpsDtos.BindOcppResult bindOcpp(
            @PathVariable String orderNo, @RequestBody(required = false) OpsDtos.BindOcppRequest request) {
        int id = orders.bindOcppTransactionId(orderNo, request == null ? null : request.meterStartWh());
        return new OpsDtos.BindOcppResult(id);
    }

    @PostMapping("/{orderNo}/meter")
    public void meter(@PathVariable String orderNo, @RequestBody OpsDtos.MeterRequest request) {
        orders.applyMeter(orderNo, request.energy(), request.amount());
    }

    @PostMapping("/{orderNo}/charging")
    public void charging(@PathVariable String orderNo, @RequestParam(required = false) String startTime) {
        orders.markCharging(orderNo, startTime == null ? null : java.time.LocalDateTime.parse(startTime));
    }

    @PostMapping("/{orderNo}/stop")
    public void stop(@PathVariable String orderNo, @RequestBody OpsDtos.StopRequest request) {
        orders.stop(orderNo, request.stopReason());
    }

    @PostMapping("/{orderNo}/fail")
    public void fail(@PathVariable String orderNo, @RequestBody OpsDtos.FailRequest request) {
        orders.fail(orderNo, request.reason());
    }

    @PostMapping("/{orderNo}/settle")
    public void settle(@PathVariable String orderNo, @RequestBody OpsDtos.SettleRequest request) {
        orders.settle(
                orderNo,
                request.energy(),
                request.amount(),
                request.startTime(),
                request.endTime(),
                request.stopReason(),
                ChargeOrder.AMOUNT_SOURCE_DEVICE);
    }

    @ExceptionHandler(OrderService.OrderConflictException.class)
    public org.springframework.http.ResponseEntity<java.util.Map<String, Object>> conflict(
            OrderService.OrderConflictException e) {
        return org.springframework.http.ResponseEntity.status(409)
                .body(java.util.Map.of("error", "ORDER_CONFLICT", "message", e.getMessage()));
    }
}
