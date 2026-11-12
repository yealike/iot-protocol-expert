package top.yealike.ipe.ops.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.ipe.ops.device.entity.DeviceInstance;
import top.yealike.ipe.ops.device.service.ChargerConnectorMapper;
import top.yealike.ipe.ops.device.service.DevicePropertyService;
import top.yealike.ipe.ops.domain.ChargeOrder;
import top.yealike.ipe.ops.dto.OpsDtos;

/** OCPP 1.6J 业务判定。协议通道只负责收发与消息关联，所有状态与账务在本服务内提交。 */
@Service
public class OcppBusinessService {

    private static final Logger log = LoggerFactory.getLogger(OcppBusinessService.class);
    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private final DeviceService devices;
    private final OrderService orders;
    private final DevicePropertyService properties;
    private final BillService bills;
    private final RateModelService rateModels;

    public OcppBusinessService(
            DeviceService devices,
            OrderService orders,
            DevicePropertyService properties,
            BillService bills,
            RateModelService rateModels) {
        this.devices = devices;
        this.orders = orders;
        this.properties = properties;
        this.bills = bills;
        this.rateModels = rateModels;
    }

    @Transactional
    public OpsDtos.DeviceView boot(OpsDtos.LoginRequest request) {
        DeviceInstance device = devices.bootOcpp(request);
        // OCPP 连接建立后由设备 Load/StatusNotification 报告枪状态，这里只初始化枪行。
        return devices.view(device);
    }

    @Transactional
    public void heartbeat(String protocolDeviceId) {
        devices.touch("OCPP16J", protocolDeviceId);
    }

    @Transactional
    public void status(OpsDtos.StatusRequest request) {
        DeviceInstance device = devices.resolve("OCPP16J", request.protocolDeviceId());
        devices.touch("OCPP16J", request.protocolDeviceId());
        if (request.connectorId() <= 0) {
            return;
        }
        validateConnector(device, request.connectorId());
        String gunNo = String.format("%02d", request.connectorId());
        boolean faulted = "Faulted".equals(request.status())
                || (request.errorCode() != null && !"NoError".equals(request.errorCode()));
        int state = switch (request.status() == null ? "" : request.status()) {
            case "Available" -> 2;
            case "Preparing", "Reserved" -> 2;
            case "Charging", "SuspendedEV", "SuspendedEVSE", "Finishing" -> 3;
            case "Unavailable" -> 0;
            case "Faulted" -> 1;
            default -> 2;
        };
        int plugged = state == 1 || state == 0 ? 0 : 1;
        properties.writeBatch(
                device.id,
                gunNo,
                ChargerConnectorMapper.fromOcpp(state, plugged, null, null, null, null, null, null),
                null);
        if (faulted) {
            log.info("OCPP 设备 {} 枪 {} 故障上报 {}", device.deviceCode, gunNo, request.errorCode());
        }
    }

    public OpsDtos.AuthorizeResult authorize(String idTag) {
        if (idTag == null || idTag.isBlank()) {
            throw new IllegalArgumentException("idTag 不能为空");
        }
        return new OpsDtos.AuthorizeResult("Accepted");
    }

    @Transactional
    public OpsDtos.StartTransactionResult startTransaction(OpsDtos.StartTransactionRequest request) {
        DeviceInstance device = devices.resolve("OCPP16J", request.protocolDeviceId());
        if (request.idTag() == null || request.idTag().isBlank()) {
            return new OpsDtos.StartTransactionResult(false, null, null, "PropertyConstraintViolation", "idTag 不能为空");
        }
        validateConnector(device, request.connectorId());
        String gunNo = String.format("%02d", request.connectorId());
        ChargeOrder order = orders.active(device.id, gunNo);
        if (order == null) {
            order = orders.create(device.id, gunNo, 2, request.idTag(), request.idTag(), "OCPP16J", true);
        } else if (!"OCPP16J".equals(order.protocol)) {
            return new OpsDtos.StartTransactionResult(
                    false, null, null, "OccurrenceConstraintViolation", "枪 " + gunNo + " 存在其它协议的进行中订单");
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
        return new OpsDtos.StartTransactionResult(true, order.orderNo, transactionId, null, null);
    }

    @Transactional
    public void meterValues(OpsDtos.MeterValuesRequest request) {
        DeviceInstance device = devices.resolve("OCPP16J", request.protocolDeviceId());
        String gunNo = String.format("%02d", Math.max(request.connectorId(), 1));
        ChargeOrder order = request.transactionId() == null
                ? orders.active(device.id, gunNo)
                : orders.byOcppTransactionId(request.transactionId());
        if (order == null) {
            return;
        }
        BigDecimal meterStart = order.meterStartWh == null ? BigDecimal.ZERO : order.meterStartWh;
        BigDecimal energyKwh = request.energyWh() == null
                ? order.energy
                : request.energyWh()
                        .subtract(meterStart)
                        .max(BigDecimal.ZERO)
                        .divide(THOUSAND, 4, RoundingMode.HALF_UP);
        BigDecimal amount =
                energyKwh.multiply(rateModels.pricePerKwh(LocalDateTime.now())).setScale(4, RoundingMode.HALF_UP);
        orders.applyMeter(order.orderNo, energyKwh, amount);
        properties.writeBatch(
                device.id,
                gunNo,
                ChargerConnectorMapper.fromOcpp(
                        3, 1, request.voltage(), request.current(), request.soc(), energyKwh, amount, order.orderNo),
                null);
    }

    @Transactional
    public OpsDtos.StopTransactionResult stopTransaction(OpsDtos.StopTransactionRequest request) {
        DeviceInstance device = devices.resolve("OCPP16J", request.protocolDeviceId());
        ChargeOrder order = orders.byOcppTransactionId(request.transactionId());
        if (order == null) {
            log.warn("OCPP 设备 {} StopTransaction 未找到整数交易号 {}，按已接收处理", device.deviceCode, request.transactionId());
            return new OpsDtos.StopTransactionResult(true, null, BigDecimal.ZERO, BigDecimal.ZERO, "交易号不存在");
        }
        BigDecimal meterStart = order.meterStartWh == null ? BigDecimal.ZERO : order.meterStartWh;
        BigDecimal meterStop = request.meterStop() == null ? meterStart : request.meterStop();
        BigDecimal energy =
                meterStop.subtract(meterStart).max(BigDecimal.ZERO).divide(THOUSAND, 4, RoundingMode.HALF_UP);
        if (order.energy != null && order.energy.compareTo(energy) > 0) {
            energy = order.energy;
        }
        LocalDateTime endedAt = request.endedAt() == null ? LocalDateTime.now() : request.endedAt();
        BigDecimal amount = energy.multiply(rateModels.pricePerKwh(endedAt)).setScale(4, RoundingMode.HALF_UP);
        int stopReason = mapStopReason(request.reason());
        orders.settle(
                order.orderNo,
                energy,
                amount,
                order.startTime,
                endedAt,
                stopReason,
                ChargeOrder.AMOUNT_SOURCE_ESTIMATE);
        ChargeOrder settled = orders.require(order.orderNo);
        bills.recordOcppSettlement(settled, rateModels.active().modelNo);
        properties.writeBatch(
                device.id,
                order.gunNo,
                ChargerConnectorMapper.fromOcpp(
                        device.allowWork ? 2 : 0,
                        0,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null,
                        energy,
                        amount,
                        order.orderNo),
                null);
        log.info(
                "OCPP 设备 {} 交易 {} 结束：{} kWh，{} 元，原因 {}",
                device.deviceCode,
                order.orderNo,
                energy,
                amount,
                request.reason());
        return new OpsDtos.StopTransactionResult(true, order.orderNo, energy, amount, "已结算");
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
