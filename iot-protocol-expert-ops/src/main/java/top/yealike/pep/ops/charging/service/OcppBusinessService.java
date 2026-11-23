package top.yealike.pep.ops.charging.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.pep.ops.api.internal.dto.AuthorizeResult;
import top.yealike.pep.ops.api.internal.dto.DeviceView;
import top.yealike.pep.ops.api.internal.dto.LoginRequest;
import top.yealike.pep.ops.api.internal.dto.MeterValuesRequest;
import top.yealike.pep.ops.api.internal.dto.StartTransactionRequest;
import top.yealike.pep.ops.api.internal.dto.StartTransactionResult;
import top.yealike.pep.ops.api.internal.dto.StatusRequest;
import top.yealike.pep.ops.api.internal.dto.StopTransactionRequest;
import top.yealike.pep.ops.api.internal.dto.StopTransactionResult;
import top.yealike.pep.ops.charging.domain.ChargeOrder;
import top.yealike.pep.ops.charging.domain.FeeTotal;
import top.yealike.pep.ops.devices.domain.DeviceInstance;
import top.yealike.pep.ops.devices.service.ChargerConnectorMapper;
import top.yealike.pep.ops.devices.service.DevicePropertyService;
import top.yealike.pep.ops.devices.service.DeviceService;

/** OCPP 1.6J 业务判定。协议通道只负责收发与消息关联，所有状态与账务在本服务内提交。 */
@Service
public class OcppBusinessService {

    private static final Logger log = LoggerFactory.getLogger(OcppBusinessService.class);
    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private final top.yealike.pep.ops.telemetry.service.OrderTelemetryService telemetry;
    private final DeviceService devices;
    private final OrderService orders;
    private final DevicePropertyService properties;
    private final BillService bills;
    private final ChargeBillingService billing;

    public OcppBusinessService(
            top.yealike.pep.ops.telemetry.service.OrderTelemetryService telemetry,
            DeviceService devices,
            OrderService orders,
            DevicePropertyService properties,
            BillService bills,
            ChargeBillingService billing) {
        this.telemetry = telemetry;
        this.devices = devices;
        this.orders = orders;
        this.properties = properties;
        this.bills = bills;
        this.billing = billing;
    }

    @Transactional
    public DeviceView boot(LoginRequest request) {
        DeviceInstance device = devices.bootOcpp(request);
        // OCPP 连接建立后由设备 Load/StatusNotification 报告枪状态，这里只初始化枪行。
        return devices.view(device);
    }

    @Transactional
    public void heartbeat(String protocolDeviceId) {
        devices.touch("OCPP16J", protocolDeviceId);
    }

    @Transactional
    public void status(StatusRequest request) {
        DeviceInstance device = devices.resolve("OCPP16J", request.protocolDeviceId());
        devices.touch("OCPP16J", request.protocolDeviceId());
        if (request.connectorId() <= 0) {
            return;
        }
        validateConnector(device, request.connectorId());
        String gunNo = String.format("%02d", request.connectorId());
        boolean faulted = "Faulted".equals(request.status())
                || (request.errorCode() != null && !"NoError".equals(request.errorCode()));
        String status = request.status() == null ? "" : request.status();
        int state = switch (status) {
            case "Available" -> 0;
            case "Preparing", "Reserved" -> 2;
            case "Charging", "SuspendedEV", "SuspendedEVSE", "Finishing" -> 3;
            case "Unavailable" -> 0;
            case "Faulted" -> 1;
            default -> throw new IllegalArgumentException("未知 OCPP 连接器状态: " + status);
        };
        // StatusNotification does not carry a separate cable-plugged flag. Available means
        // the connector has no vehicle, while Preparing and charging states imply a plug.
        // Faulted/Unavailable do not prove that the cable was removed, so preserve the last
        // known value instead of silently clearing it.
        int plugged = switch (status) {
            case "Available" -> 0;
            case "Preparing", "Reserved", "Charging", "SuspendedEV", "SuspendedEVSE", "Finishing" -> 1;
            default -> properties.boolOf(device.id, gunNo, "plugged", false) ? 1 : 0;
        };
        properties.writeBatch(
                device.id,
                gunNo,
                ChargerConnectorMapper.fromOcpp(state, plugged, null, null, null, null, null, null),
                null);
        if (faulted) {
            log.info("OCPP 设备 {} 枪 {} 故障上报 {}", device.deviceCode, gunNo, request.errorCode());
        }
    }

    public AuthorizeResult authorize(String idTag) {
        if (idTag == null || idTag.isBlank()) {
            throw new IllegalArgumentException("idTag 不能为空");
        }
        return new AuthorizeResult("Accepted");
    }

    @Transactional
    public StartTransactionResult startTransaction(StartTransactionRequest request) {
        DeviceInstance device = devices.resolve("OCPP16J", request.protocolDeviceId());
        if (request.idTag() == null || request.idTag().isBlank()) {
            return new StartTransactionResult(false, null, null, "PropertyConstraintViolation", "idTag 不能为空");
        }
        validateConnector(device, request.connectorId());
        String gunNo = String.format("%02d", request.connectorId());
        ChargeOrder order = orders.active(device.id, gunNo);
        if (order == null) {
            order = orders.create(device.id, gunNo, 2, request.idTag(), request.idTag(), "OCPP16J", true);
        } else {
            order = orders.lockByOrderNo(order.orderNo);
            if (order == null) {
                return new StartTransactionResult(false, null, null, "OccurrenceConstraintViolation", "待启动订单已不存在");
            }
            if (!"OCPP16J".equals(order.protocol)) {
                return new StartTransactionResult(
                        false, null, null, "OccurrenceConstraintViolation", "枪 " + gunNo + " 存在其它协议的进行中订单");
            }
            if (order.status != ChargeOrder.Status.PENDING && order.status != ChargeOrder.Status.CHARGING) {
                return new StartTransactionResult(
                        false, null, null, "OccurrenceConstraintViolation", "订单状态不允许启动: " + order.status);
            }
        }
        if (!matchesIdTag(order, request.idTag())) {
            return new StartTransactionResult(false, null, null, "OccurrenceConstraintViolation", "idTag 与待启动订单不一致");
        }
        int transactionId = orders.bindOcppTransactionId(order.orderNo, request.meterStart());
        orders.markCharging(order.orderNo, request.startedAt());
        properties.writeBatch(
                device.id,
                gunNo,
                ChargerConnectorMapper.fromOcpp(
                        3, 1, null, null, null, BigDecimal.ZERO, BigDecimal.ZERO, order.orderNo),
                null);
        log.info("OCPP 设备 {} 枪 {} 开始交易 {}（整数交易号 {}）", device.deviceCode, gunNo, order.orderNo, transactionId);
        return new StartTransactionResult(true, order.orderNo, transactionId, null, null);
    }

    @Transactional
    public void meterValues(MeterValuesRequest request) {
        DeviceInstance device = devices.resolve("OCPP16J", request.protocolDeviceId());
        if (request.connectorId() <= 0) return;
        String gunNo = String.format("%02d", request.connectorId());
        ChargeOrder order = request.transactionId() == null
                ? lockActive(device.id, gunNo)
                : orders.lockOcppTransactionId(device.id, request.transactionId());
        if (order == null) {
            return;
        }
        if (!java.util.Objects.equals(order.deviceId, device.id)
                || !java.util.Objects.equals(order.gunNo, gunNo)
                || order.status != ChargeOrder.Status.CHARGING) return;
        java.time.Instant sampleTime = java.time.Instant.now();
        String timeSource = "RECEIVED";
        if (request.sampleTime() != null) {
            try {
                sampleTime =
                        java.time.OffsetDateTime.parse(request.sampleTime()).toInstant();
                timeSource = "DEVICE";
            } catch (java.time.format.DateTimeParseException ignored) {
                // Missing or malformed timestamps use the explicitly labelled receive time.
            }
        }
        telemetry.record(
                order,
                sampleTime,
                timeSource,
                request.voltage(),
                request.current(),
                request.powerKw(),
                request.temperature(),
                null,
                request.soc() == null ? null : BigDecimal.valueOf(request.soc()));
        BigDecimal meterStart = order.meterStartWh == null ? BigDecimal.ZERO : order.meterStartWh;
        BigDecimal energyKwh = request.energyWh() == null
                ? order.energy
                : request.energyWh()
                        .subtract(meterStart)
                        .max(BigDecimal.ZERO)
                        .divide(THOUSAND, 4, RoundingMode.HALF_UP);
        FeeTotal fee =
                billing.applyOcppMeter(order, energyKwh, LocalDateTime.ofInstant(sampleTime, ZoneId.systemDefault()));
        properties.writeBatch(
                device.id,
                gunNo,
                ChargerConnectorMapper.fromOcpp(
                        3,
                        1,
                        request.voltage(),
                        request.current(),
                        request.soc(),
                        fee.energy(),
                        fee.amount(),
                        order.orderNo),
                null);
    }

    private ChargeOrder lockActive(Long deviceId, String gunNo) {
        ChargeOrder active = orders.active(deviceId, gunNo);
        return active == null ? null : orders.lockByOrderNo(active.orderNo);
    }

    @Transactional
    public StopTransactionResult stopTransaction(StopTransactionRequest request) {
        DeviceInstance device = devices.resolve("OCPP16J", request.protocolDeviceId());
        ChargeOrder order = orders.lockOcppTransactionId(device.id, request.transactionId());
        if (order == null) {
            log.warn("OCPP 设备 {} StopTransaction 未找到整数交易号 {}", device.deviceCode, request.transactionId());
            return rejectedStop("交易号不存在");
        }
        if (!java.util.Objects.equals(order.deviceId, device.id) || !"OCPP16J".equals(order.protocol)) {
            log.warn("OCPP 设备 {} 拒绝交易 {} 的停止上报：订单归属设备 {}", device.deviceCode, request.transactionId(), order.deviceId);
            return rejectedStop("交易号不属于当前设备");
        }
        if (!ownsValidGun(device, order.gunNo)) {
            log.warn("OCPP 设备 {} 拒绝交易 {} 的停止上报：订单枪号 {} 无效", device.deviceCode, request.transactionId(), order.gunNo);
            return rejectedStop("交易所属枪号无效");
        }
        if (order.status == ChargeOrder.Status.SETTLED) {
            // StopTransaction retries are expected after a lost CALLRESULT. Re-ack the same
            // transaction without creating another bill.
            return new StopTransactionResult(
                    true,
                    order.orderNo,
                    order.energy == null ? BigDecimal.ZERO : order.energy,
                    order.electricityFee == null ? BigDecimal.ZERO : order.electricityFee,
                    order.serviceFee == null ? BigDecimal.ZERO : order.serviceFee,
                    order.amount == null ? BigDecimal.ZERO : order.amount,
                    "订单已结算，重复上报已忽略");
        }
        if (order.status != ChargeOrder.Status.CHARGING && order.status != ChargeOrder.Status.STOPPED) {
            log.warn("OCPP 设备 {} 拒绝交易 {} 的停止上报：订单状态 {}", device.deviceCode, request.transactionId(), order.status);
            return rejectedStop("订单状态不允许结算: " + order.status);
        }
        BigDecimal meterStart = order.meterStartWh == null ? BigDecimal.ZERO : order.meterStartWh;
        BigDecimal meterStop = request.meterStop() == null ? meterStart : request.meterStop();
        BigDecimal energy =
                meterStop.subtract(meterStart).max(BigDecimal.ZERO).divide(THOUSAND, 4, RoundingMode.HALF_UP);
        if (order.energy != null && order.energy.compareTo(energy) > 0) {
            energy = order.energy;
        }
        LocalDateTime endedAt = request.endedAt() == null ? LocalDateTime.now() : request.endedAt();
        int stopReason = mapStopReason(request.reason());
        FeeTotal fee = billing.applyOcppMeter(order, energy, endedAt);
        orders.settle(
                order.orderNo,
                fee.energy(),
                fee.electricityFee(),
                fee.serviceFee(),
                fee.amount(),
                fee.lossEnergy(),
                order.startTime,
                endedAt,
                stopReason,
                ChargeOrder.AMOUNT_SOURCE_ESTIMATE);
        ChargeOrder settled = orders.require(order.orderNo);
        bills.recordOcppSettlement(settled, String.valueOf(request.transactionId()));
        properties.writeBatch(
                device.id,
                order.gunNo,
                ChargerConnectorMapper.fromOcpp(
                        device.allowWork ? 2 : 0,
                        0,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null,
                        settled.energy,
                        settled.amount,
                        order.orderNo),
                null);
        log.info(
                "OCPP 设备 {} 交易 {} 结束：{} kWh，{} 元，原因 {}",
                device.deviceCode,
                order.orderNo,
                settled.energy,
                settled.amount,
                request.reason());
        return new StopTransactionResult(
                true, order.orderNo, settled.energy, settled.electricityFee, settled.serviceFee, settled.amount, "已结算");
    }

    private static StopTransactionResult rejectedStop(String message) {
        return new StopTransactionResult(
                false, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, message);
    }

    private boolean ownsValidGun(DeviceInstance device, String gunNo) {
        if (gunNo == null || !gunNo.matches("[0-9]{2}")) return false;
        int connectorId = Integer.parseInt(gunNo);
        return connectorId >= 1 && connectorId <= Math.max(devices.gunCount(device.id), 1);
    }

    private static boolean matchesIdTag(ChargeOrder order, String idTag) {
        return java.util.Objects.equals(order.logicalCard, idTag)
                || java.util.Objects.equals(order.physicalCard, idTag);
    }

    private void validateConnector(DeviceInstance device, int connectorId) {
        if (connectorId < 1 || connectorId > Math.max(devices.gunCount(device.id), 1)) {
            throw new IllegalArgumentException("connectorId 超出设备枪数: " + connectorId);
        }
    }

    private static int mapStopReason(String reason) {
        return switch (reason == null ? "" : reason) {
            case "Remote" -> 0x40;
            case "EmergencyStop" -> 0x72;
            case "EVDisconnected" -> 0x45;
            case "PowerLoss", "Reboot" -> 0x83;
            default -> 0x45;
        };
    }
}
