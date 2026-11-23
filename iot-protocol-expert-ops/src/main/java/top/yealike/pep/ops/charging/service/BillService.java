package top.yealike.pep.ops.charging.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import top.yealike.pep.ops.api.internal.dto.BillRequest;
import top.yealike.pep.ops.charging.domain.Bill;
import top.yealike.pep.ops.charging.domain.ChargeOrder;
import top.yealike.pep.ops.charging.domain.FeeTotal;
import top.yealike.pep.ops.charging.domain.RateSnapshot;
import top.yealike.pep.ops.charging.domain.YkcBillCalculation;
import top.yealike.pep.ops.charging.dto.BillView;
import top.yealike.pep.ops.charging.mapper.BillMapper;
import top.yealike.pep.ops.devices.domain.DeviceInstance;
import top.yealike.pep.ops.devices.service.DeviceService;

@Service
public class BillService {

    public static final String SOURCE_YKC_TRANSACTION = "YKC_TRANSACTION";
    public static final String SOURCE_OCPP_STOP = "OCPP_STOP_TRANSACTION";

    private final BillMapper bills;
    private final DeviceService devices;
    private final OrderService orders;
    private final ChargeBillingService billing;
    private final ObjectMapper json;

    public BillService(
            BillMapper bills,
            DeviceService devices,
            OrderService orders,
            ChargeBillingService billing,
            ObjectMapper json) {
        this.bills = bills;
        this.devices = devices;
        this.orders = orders;
        this.billing = billing;
        this.json = json;
    }

    /** 云快充 0x3B 交易记录：先按设备与协议交易号定位平台订单，再落电费、服务费和四段明细。 */
    @Transactional
    public Bill acceptYkc(BillRequest request) {
        DeviceInstance device = devices.resolve("YKC16", request.protocolDeviceId());
        bills.lockSourceTransaction(device.id, "YKC16", SOURCE_YKC_TRANSACTION, request.transactionNo());
        var existing = bills.findByDeviceAndProtocolAndSourceTypeAndSourceTransactionNo(
                device.id, "YKC16", SOURCE_YKC_TRANSACTION, request.transactionNo());
        if (existing.isPresent()) {
            return existing.get();
        }

        ChargeOrder order =
                orders.findByExternal(device.id, "YKC16", OrderService.YKC_TRANSACTION, request.transactionNo());
        if (order != null) {
            order = orders.lockByOrderNo(order.orderNo);
        }
        Bill bill = new Bill();
        bill.deviceId = device.id;
        bill.protocol = "YKC16";
        bill.sourceType = SOURCE_YKC_TRANSACTION;
        bill.sourceTransactionNo = request.transactionNo();
        bill.energy = zero(request.totalEnergy());
        bill.amount = zero(request.totalAmount());
        bill.currency = "CNY";
        bill.amountType = ChargeOrder.AMOUNT_SOURCE_DEVICE;
        bill.stopReason = request.stopReason();
        bill.endedAt = request.endTime() == null ? request.tradeTime() : request.endTime();
        bill.receivedAt = LocalDateTime.now();
        bill.createdAt = LocalDateTime.now();
        bill.rawPayload = truncate(request.rawHex(), 8000);
        bill.partial = true;

        if (order == null) {
            RateSnapshot snapshot = billing.currentSnapshot();
            YkcBillCalculation calculation = billing.calculateYkcBill(snapshot, request);
            FeeTotal total = calculation.total();
            bill.energy = total.energy();
            bill.electricityFee = total.electricityFee();
            bill.serviceFee = total.serviceFee();
            bill.amount = total.amount();
            bill.lossEnergy = total.lossEnergy();
            bill.rateModelNo = snapshot.modelNo();
            bill.rateSnapshot = billing.json(snapshot);
            bill.feeBreakdown = total.breakdown();
            bill.accepted = true;
            bill.rejectReason = "平台无对应订单，按离线账单入库";
        } else if (!Objects.equals(order.deviceId, device.id) || !Objects.equals(order.gunNo, request.gunNo())) {
            bill.accepted = false;
            bill.rejectReason = "订单设备或枪号不匹配";
        } else {
            FeeTotal fee = billing.applyYkcBill(order, request);
            orders.settle(
                    order.orderNo,
                    fee.energy(),
                    fee.electricityFee(),
                    fee.serviceFee(),
                    fee.amount(),
                    fee.lossEnergy(),
                    request.startTime(),
                    request.endTime(),
                    request.stopReason(),
                    ChargeOrder.AMOUNT_SOURCE_DEVICE);
            ChargeOrder settled = orders.require(order.orderNo);
            if (settled.status != ChargeOrder.Status.SETTLED) {
                throw new IllegalStateException("设备账单无法结算订单: " + order.orderNo);
            }
            bill.orderId = settled.id;
            bill.orderNo = settled.orderNo;
            bill.energy = zero(settled.energy);
            bill.electricityFee = settled.electricityFee;
            bill.serviceFee = settled.serviceFee;
            bill.amount = zero(settled.amount);
            bill.lossEnergy = settled.lossEnergy;
            bill.rateModelNo = settled.rateModelNo;
            bill.rateSnapshot = settled.rateSnapshot;
            bill.feeBreakdown = settled.feeBreakdown;
            bill.partial = fee.partial();
            bill.accepted = true;
        }
        return bills.save(bill);
    }

    /** OCPP StopTransaction 的平台估算账单，与订单结算在同一事务内。 */
    @Transactional
    public Bill recordOcppSettlement(ChargeOrder order, String transactionNo) {
        var existing = bills.findByDeviceAndProtocolAndSourceTypeAndSourceTransactionNo(
                order.deviceId, "OCPP16J", SOURCE_OCPP_STOP, transactionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        Bill bill = new Bill();
        bill.orderId = order.id;
        bill.orderNo = order.orderNo;
        bill.deviceId = order.deviceId;
        bill.protocol = "OCPP16J";
        bill.sourceType = SOURCE_OCPP_STOP;
        bill.sourceTransactionNo = transactionNo;
        bill.energy = zero(order.energy);
        bill.electricityFee = order.electricityFee;
        bill.serviceFee = order.serviceFee;
        bill.amount = zero(order.amount);
        bill.lossEnergy = order.lossEnergy;
        bill.rateModelNo = order.rateModelNo;
        bill.rateSnapshot = order.rateSnapshot;
        bill.feeBreakdown = order.feeBreakdown;
        bill.partial = false;
        bill.currency = "CNY";
        bill.amountType = ChargeOrder.AMOUNT_SOURCE_ESTIMATE;
        bill.stopReason = order.stopReason;
        bill.endedAt = order.endTime;
        bill.receivedAt = LocalDateTime.now();
        bill.createdAt = LocalDateTime.now();
        bill.accepted = true;
        bill.rawPayload = json.writeValueAsString(Map.of(
                "rateModelNo", Objects.requireNonNullElse(order.rateModelNo, ""), "ocppTransactionId", transactionNo));
        return bills.save(bill);
    }

    public List<Bill> search(
            Long deviceId,
            String protocol,
            String sourceTransactionNo,
            LocalDateTime from,
            LocalDateTime to,
            Boolean accepted) {
        return bills.search(deviceId, protocol, sourceTransactionNo, from, to, accepted, 500);
    }

    public List<Bill> byOrder(Long orderId) {
        return bills.findAllByOrderId(orderId);
    }

    public BillView view(Bill bill) {
        String deviceSn = null;
        try {
            deviceSn = devices.get(bill.deviceId).deviceCode;
        } catch (RuntimeException ignored) {
            // A bill remains readable after its device record is removed.
        }
        return new BillView(
                bill.id,
                bill.orderId,
                bill.orderNo,
                bill.deviceId,
                deviceSn,
                bill.protocol,
                bill.sourceType,
                bill.sourceTransactionNo,
                bill.energy,
                bill.electricityFee,
                bill.serviceFee,
                bill.amount,
                bill.lossEnergy,
                bill.rateModelNo,
                bill.partial,
                bill.currency,
                bill.amountType,
                bill.stopReason,
                bill.endedAt,
                bill.receivedAt,
                bill.accepted,
                bill.rejectReason,
                bill.createdAt);
    }

    private static BigDecimal zero(BigDecimal value) {
        return Objects.requireNonNullElse(value, BigDecimal.ZERO);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
