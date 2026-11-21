package top.yealike.pep.ops.api.internal;

import org.springframework.web.bind.annotation.*;
import top.yealike.pep.ops.api.dto.OpsDtos;
import top.yealike.pep.ops.charging.domain.Bill;
import top.yealike.pep.ops.charging.service.BillService;
import top.yealike.pep.ops.devices.service.ChargerConnectorMapper;
import top.yealike.pep.ops.devices.service.DevicePropertyService;

/**
 * 协议通道 → 平台 的遥测入口。
 *
 * <p>契约不变：仍然收 {@link OpsDtos.RealtimeRequest}。枪位实时值不再写 {@code gun_state}，
 * 而是翻译成通道型属性写进属性表。
 */
@RestController
@RequestMapping("/internal")
public class InternalTelemetryController {

    private final top.yealike.pep.ops.telemetry.OrderTelemetryService telemetry;
    private final top.yealike.pep.ops.devices.service.DeviceService devices;
    private final BillService bills;
    private final top.yealike.pep.ops.devices.service.FrameLogService frames;
    private final DevicePropertyService properties;
    private final top.yealike.pep.ops.charging.service.RateModelService rates;
    private final top.yealike.pep.ops.charging.service.OrderService orders;

    public InternalTelemetryController(
            top.yealike.pep.ops.telemetry.OrderTelemetryService telemetry,
            top.yealike.pep.ops.devices.service.DeviceService devices,
            BillService bills,
            top.yealike.pep.ops.devices.service.FrameLogService frames,
            DevicePropertyService properties,
            top.yealike.pep.ops.charging.service.RateModelService rates,
            top.yealike.pep.ops.charging.service.OrderService orders) {
        this.telemetry = telemetry;
        this.devices = devices;
        this.bills = bills;
        this.frames = frames;
        this.properties = properties;
        this.rates = rates;
        this.orders = orders;
    }

    @PostMapping("/bills/ykc")
    public OpsDtos.BillResult acceptYkc(@RequestBody OpsDtos.BillRequest request) {
        Bill bill = bills.acceptYkc(request);
        return new OpsDtos.BillResult(bill.accepted, bill.rejectReason, bill.id, bill.orderNo);
    }

    @PostMapping("/frames")
    public void frame(@RequestBody OpsDtos.FrameRequest request) {
        frames.record(request);
    }

    @PostMapping("/gun-states")
    @org.springframework.transaction.annotation.Transactional
    public void gunState(@RequestBody OpsDtos.RealtimeRequest request) {
        properties.writeBatchByProtocol(
                request.protocol(),
                request.protocolDeviceId(),
                request.gunNo(),
                ChargerConnectorMapper.fromRealtime(request),
                null);
        orders.applyRealtime(
                request.protocol(),
                request.protocolDeviceId(),
                request.gunNo(),
                request.state(),
                request.chargedEnergy(),
                request.chargedAmount(),
                request.transactionNo());
        if (request.state() == 3) {
            var device = devices.resolve(request.protocol(), request.protocolDeviceId());
            var order =
                    request.transactionNo() == null || request.transactionNo().isBlank()
                            ? orders.active(device.id, request.gunNo())
                            : orders.findByExternal(
                                    device.id,
                                    request.protocol(),
                                    top.yealike.pep.ops.charging.service.OrderService.YKC_TRANSACTION,
                                    request.transactionNo());
            if (order != null
                    && java.util.Objects.equals(order.deviceId, device.id)
                    && java.util.Objects.equals(order.gunNo, request.gunNo())
                    && (order.status == top.yealike.pep.ops.charging.domain.ChargeOrder.Status.CHARGING)) {
                telemetry.record(
                        order,
                        java.time.Instant.now(),
                        "RECEIVED",
                        request.outputVoltage(),
                        request.outputCurrent(),
                        null,
                        java.math.BigDecimal.valueOf(request.gunLineTemp()),
                        java.math.BigDecimal.valueOf(request.batteryMaxTemp()),
                        request.soc() < 0 ? null : java.math.BigDecimal.valueOf(request.soc()));
            }
        }
    }

    /** 通用遥测入口：任意设备类型的属性批量上报。 */
    @PostMapping("/properties")
    public int properties(@RequestBody TelemetryRequest request) {
        return properties.writeGatewayBatch(
                request.protocol(),
                request.protocolDeviceId(),
                request.channelNo(),
                request.values(),
                request.collectedAt());
    }

    public record TelemetryRequest(
            String protocol,
            String protocolDeviceId,
            String channelNo,
            java.util.Map<String, Object> values,
            java.time.LocalDateTime collectedAt) {}

    @GetMapping("/rate-models/active")
    public OpsDtos.RateModelView activeRateModel() {
        return rates.view(rates.active());
    }

    @GetMapping("/rate-models/verify")
    public boolean verifyRateModel(@RequestParam String modelNo) {
        return rates.verify(modelNo);
    }

    @GetMapping("/rate-models/price")
    public java.math.BigDecimal price(@RequestParam String at) {
        return rates.pricePerKwh(java.time.LocalDateTime.parse(at));
    }
}
