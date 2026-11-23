package top.yealike.pep.ops.api.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import top.yealike.pep.ops.api.internal.dto.BindOcppRequest;
import top.yealike.pep.ops.api.internal.dto.BindOcppResult;
import top.yealike.pep.ops.api.internal.dto.FailRequest;
import top.yealike.pep.ops.api.internal.dto.MeterRequest;
import top.yealike.pep.ops.api.internal.dto.SettleRequest;
import top.yealike.pep.ops.api.internal.dto.StartRequest;
import top.yealike.pep.ops.api.internal.dto.StartResult;
import top.yealike.pep.ops.api.internal.dto.StopRequest;
import top.yealike.pep.ops.charging.domain.ChargeOrder;
import top.yealike.pep.ops.charging.domain.OrderConflictException;
import top.yealike.pep.ops.charging.dto.OrderView;
import top.yealike.pep.ops.charging.service.OrderService;
import top.yealike.pep.ops.devices.domain.DeviceInstance;
import top.yealike.pep.ops.devices.service.DeviceService;

@RestController
@RequestMapping(path = "/internal/orders", produces = "application/json")
@Tag(name = "内部订单", description = "协议通道内部使用的订单创建、状态推进与结算接口")
@SecurityRequirement(name = "opsServiceToken")
public class InternalOrderController {

    private final OrderService orders;
    private final DeviceService devices;

    public InternalOrderController(OrderService orders, DeviceService devices) {
        this.orders = orders;
        this.devices = devices;
    }

    /** 设备主动申请启动（云快充 0x31）的业务判定。 */
    @PostMapping("/start")
    @Operation(operationId = "internalStartOrder", summary = "设备申请启动充电")
    public StartResult start(@RequestBody StartRequest request) {
        DeviceInstance device = devices.resolve(request.protocol(), request.protocolDeviceId());
        String gunNo = request.gunNo();
        if (gunNo == null
                || !gunNo.matches("[0-9]{2}")
                || Integer.parseInt(gunNo) < 1
                || Integer.parseInt(gunNo) > devices.gunCount(device.id)) {
            throw new IllegalArgumentException("枪号不属于设备");
        }
        if (!device.allowWork) {
            return new StartResult(false, null, null, 0x05, "设备已停用");
        }
        if (orders.active(device.id, gunNo) != null) {
            return new StartResult(false, null, null, 0x0A, "枪已在充电");
        }
        ChargeOrder order = orders.create(
                device.id,
                gunNo,
                request.startType(),
                request.account(),
                "00000000",
                device.protocol,
                "OCPP16J".equals(device.protocol));
        return new StartResult(true, order.orderNo, orders.transactionNo(order), 0x00, "启动通过");
    }

    @GetMapping("/active")
    @Operation(operationId = "internalActiveOrder", summary = "查询枪口进行中订单")
    public OrderView active(
            @Parameter(description = "协议编码") @RequestParam String protocol,
            @Parameter(description = "协议侧设备编号") @RequestParam String protocolDeviceId,
            @Parameter(description = "枪号") @RequestParam String gunNo) {
        DeviceInstance device = devices.resolve(protocol, protocolDeviceId);
        ChargeOrder order = orders.active(device.id, gunNo);
        return order == null ? null : orders.view(order);
    }

    @GetMapping("/by-no/{orderNo}")
    @Operation(operationId = "internalOrderByNo", summary = "按订单号查询订单")
    public OrderView byNo(@Parameter(description = "平台订单号") @PathVariable String orderNo) {
        ChargeOrder order = orders.findByOrderNo(orderNo);
        return order == null ? null : orders.view(order);
    }

    @GetMapping("/by-ocpp")
    @Operation(operationId = "internalOrderByOcppTransaction", summary = "按 OCPP 交易号查询订单")
    public OrderView byOcpp(
            @Parameter(description = "协议编码") @RequestParam String protocol,
            @Parameter(description = "协议侧设备编号") @RequestParam String protocolDeviceId,
            @Parameter(description = "OCPP transactionId") @RequestParam Integer transactionId) {
        DeviceInstance device = devices.resolve(protocol, protocolDeviceId);
        ChargeOrder order = orders.byOcppTransactionId(device.id, transactionId);
        return order == null ? null : orders.view(order);
    }

    @GetMapping("/by-transaction")
    @Operation(operationId = "internalOrderByTransaction", summary = "按外部交易号查询订单")
    public OrderView byTransaction(
            @Parameter(description = "协议编码") @RequestParam String protocol,
            @Parameter(description = "协议侧设备编号") @RequestParam String protocolDeviceId,
            @Parameter(description = "外部交易号") @RequestParam String transactionNo) {
        ChargeOrder order = orders.requireExternal(protocol, protocolDeviceId, transactionNo);
        return orders.view(order);
    }

    @PostMapping("/{orderNo}/bind-ocpp")
    @Operation(operationId = "internalBindOcppTransaction", summary = "绑定 OCPP 交易号")
    public BindOcppResult bindOcpp(
            @Parameter(description = "平台订单号") @PathVariable String orderNo,
            @RequestBody(required = false) BindOcppRequest request) {
        int id = orders.bindOcppTransactionId(orderNo, request == null ? null : request.meterStartWh());
        return new BindOcppResult(id);
    }

    @PostMapping("/{orderNo}/meter")
    @Operation(operationId = "internalApplyOrderMeter", summary = "更新订单计量")
    public void meter(
            @Parameter(description = "平台订单号") @PathVariable String orderNo, @RequestBody MeterRequest request) {
        orders.applyMeter(orderNo, request.energy(), request.amount());
    }

    @PostMapping("/{orderNo}/charging")
    @Operation(operationId = "internalMarkOrderCharging", summary = "标记订单开始充电")
    public void charging(
            @Parameter(description = "平台订单号") @PathVariable String orderNo,
            @Parameter(description = "开始时间，ISO-8601") @RequestParam(required = false) String startTime) {
        orders.markCharging(orderNo, startTime == null ? null : java.time.LocalDateTime.parse(startTime));
    }

    @PostMapping("/by-transaction/{transactionNo}/charging")
    @Operation(operationId = "internalMarkOrderChargingByTransaction", summary = "按外部交易号标记开始充电")
    public void chargingByTransaction(
            @Parameter(description = "外部交易号") @PathVariable String transactionNo,
            @Parameter(description = "协议编码") @RequestParam String protocol,
            @Parameter(description = "协议侧设备编号") @RequestParam String protocolDeviceId,
            @Parameter(description = "开始时间，ISO-8601") @RequestParam(required = false) String startTime) {
        orders.markChargingByTransaction(
                protocol,
                protocolDeviceId,
                transactionNo,
                startTime == null ? null : java.time.LocalDateTime.parse(startTime));
    }

    @PostMapping("/{orderNo}/stop")
    @Operation(operationId = "internalStopOrder", summary = "停止订单充电")
    public void stop(@Parameter(description = "平台订单号") @PathVariable String orderNo, @RequestBody StopRequest request) {
        orders.stop(orderNo, request.stopReason());
    }

    @PostMapping("/{orderNo}/fail")
    @Operation(operationId = "internalFailOrder", summary = "标记订单失败")
    public void fail(@Parameter(description = "平台订单号") @PathVariable String orderNo, @RequestBody FailRequest request) {
        orders.fail(orderNo, request.reason());
    }

    @PostMapping("/by-transaction/{transactionNo}/fail")
    @Operation(operationId = "internalFailOrderByTransaction", summary = "按外部交易号标记订单失败")
    public void failByTransaction(
            @Parameter(description = "外部交易号") @PathVariable String transactionNo,
            @Parameter(description = "协议编码") @RequestParam String protocol,
            @Parameter(description = "协议侧设备编号") @RequestParam String protocolDeviceId,
            @RequestBody FailRequest request) {
        orders.failByTransaction(protocol, protocolDeviceId, transactionNo, request.reason());
    }

    @PostMapping("/{orderNo}/settle")
    @Operation(operationId = "internalSettleOrder", summary = "结算订单")
    public void settle(
            @Parameter(description = "平台订单号") @PathVariable String orderNo, @RequestBody SettleRequest request) {
        orders.settle(
                orderNo,
                request.energy(),
                request.electricityFee(),
                request.serviceFee(),
                request.amount(),
                request.lossEnergy(),
                request.startTime(),
                request.endTime(),
                request.stopReason(),
                ChargeOrder.AMOUNT_SOURCE_DEVICE);
    }

    @ExceptionHandler(OrderConflictException.class)
    public org.springframework.http.ResponseEntity<java.util.Map<String, Object>> conflict(OrderConflictException e) {
        return org.springframework.http.ResponseEntity.status(409)
                .body(java.util.Map.of("error", "ORDER_CONFLICT", "message", e.getMessage()));
    }
}
