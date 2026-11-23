package top.yealike.pep.ops.api.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import top.yealike.pep.ops.api.internal.dto.BillRequest;
import top.yealike.pep.ops.api.internal.dto.BillResult;
import top.yealike.pep.ops.api.internal.dto.FrameRequest;
import top.yealike.pep.ops.api.internal.dto.RealtimeRequest;
import top.yealike.pep.ops.api.internal.dto.TelemetryRequest;
import top.yealike.pep.ops.charging.domain.Bill;
import top.yealike.pep.ops.charging.dto.RateModelView;
import top.yealike.pep.ops.charging.service.BillService;
import top.yealike.pep.ops.charging.service.OrderService;
import top.yealike.pep.ops.charging.service.RateModelService;
import top.yealike.pep.ops.devices.service.ChargerConnectorMapper;
import top.yealike.pep.ops.devices.service.DevicePropertyService;
import top.yealike.pep.ops.devices.service.DeviceService;
import top.yealike.pep.ops.devices.service.FrameLogService;
import top.yealike.pep.ops.telemetry.service.OrderTelemetryService;

/**
 * 协议通道 → 平台 的遥测入口。
 *
 * <p>契约不变：仍然收 {@link RealtimeRequest}。枪位实时值不再写 {@code gun_state}，
 * 而是翻译成通道型属性写进属性表。
 */
@RestController
@RequestMapping(path = "/internal", produces = "application/json")
@Tag(name = "内部遥测", description = "协议通道上报的账单、报文、枪口状态与属性数据")
@SecurityRequirement(name = "opsServiceToken")
public class InternalTelemetryController {

    private final OrderTelemetryService telemetry;
    private final DeviceService devices;
    private final BillService bills;
    private final FrameLogService frames;
    private final DevicePropertyService properties;
    private final RateModelService rates;
    private final OrderService orders;

    public InternalTelemetryController(
            OrderTelemetryService telemetry,
            DeviceService devices,
            BillService bills,
            FrameLogService frames,
            DevicePropertyService properties,
            RateModelService rates,
            OrderService orders) {
        this.telemetry = telemetry;
        this.devices = devices;
        this.bills = bills;
        this.frames = frames;
        this.properties = properties;
        this.rates = rates;
        this.orders = orders;
    }

    @PostMapping("/bills/ykc")
    @Operation(operationId = "internalAcceptYkcBill", summary = "接收云快充账单")
    public BillResult acceptYkc(@RequestBody BillRequest request) {
        Bill bill = bills.acceptYkc(request);
        return new BillResult(bill.accepted, bill.rejectReason, bill.id, bill.orderNo);
    }

    @PostMapping("/frames")
    @Operation(operationId = "internalRecordFrame", summary = "记录协议报文")
    public void frame(@RequestBody FrameRequest request) {
        frames.record(request);
    }

    @PostMapping("/gun-states")
    @org.springframework.transaction.annotation.Transactional
    @Operation(operationId = "internalReportGunState", summary = "上报枪口实时状态")
    public void gunState(@RequestBody RealtimeRequest request) {
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
            var order = request.transactionNo() == null
                            || request.transactionNo().isBlank()
                    ? orders.active(device.id, request.gunNo())
                    : orders.findByExternal(
                            device.id, request.protocol(), OrderService.YKC_TRANSACTION, request.transactionNo());
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
    @Operation(operationId = "internalReportProperties", summary = "批量上报设备属性")
    public int properties(@RequestBody TelemetryRequest request) {
        return properties.writeGatewayBatch(
                request.protocol(),
                request.protocolDeviceId(),
                request.channelNo(),
                request.values(),
                request.collectedAt());
    }

    @GetMapping("/rate-models/active")
    @Operation(operationId = "internalActiveRateModel", summary = "查询当前生效费率")
    public RateModelView activeRateModel() {
        return rates.view(rates.active());
    }

    @GetMapping("/rate-models/verify")
    @Operation(operationId = "internalVerifyRateModel", summary = "校验费率模型")
    public boolean verifyRateModel(@Parameter(description = "费率模型编号") @RequestParam String modelNo) {
        return rates.verify(modelNo);
    }
}
