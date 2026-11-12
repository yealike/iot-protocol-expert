package top.yealike.ipe.ops.service;

import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.ipe.ops.device.entity.DeviceInstance;
import top.yealike.ipe.ops.domain.Bill;
import top.yealike.ipe.ops.domain.ChargeOrder;
import top.yealike.ipe.ops.dto.OpsDtos;
import top.yealike.ipe.ops.repository.BillRepository;

@Service
public class BillService {

    public static final String SOURCE_YKC_TRANSACTION = "YKC_TRANSACTION";
    public static final String SOURCE_OCPP_STOP = "OCPP_STOP_TRANSACTION";

    private final BillRepository bills;
    private final DeviceService devices;
    private final OrderService orders;

    public BillService(BillRepository bills, DeviceService devices, OrderService orders) {
        this.bills = bills;
        this.devices = devices;
        this.orders = orders;
    }

    /** 云快充 0x3B 交易记录：按协议 + 来源交易号幂等；订单存在时在同一事务内结算并生成账单。 */
    @Transactional
    public Bill acceptYkc(OpsDtos.BillRequest request) {
        var existing = bills.findByProtocolAndSourceTypeAndSourceTransactionNo(
                "YKC16", SOURCE_YKC_TRANSACTION, request.transactionNo());
        if (existing.isPresent()) {
            return existing.get();
        }
        DeviceInstance device = devices.resolve("YKC16", request.protocolDeviceId());
        Bill bill = new Bill();
        bill.deviceId = device.id;
        bill.protocol = "YKC16";
        bill.sourceType = SOURCE_YKC_TRANSACTION;
        bill.sourceTransactionNo = request.transactionNo();
        bill.energy = request.totalEnergy() == null ? BigDecimal.ZERO : request.totalEnergy();
        bill.amount = request.totalAmount() == null ? BigDecimal.ZERO : request.totalAmount();
        bill.currency = "CNY";
        bill.amountType = ChargeOrder.AMOUNT_SOURCE_DEVICE;
        bill.stopReason = request.stopReason();
        bill.endedAt = request.endTime() == null ? request.tradeTime() : request.endTime();
        bill.receivedAt = LocalDateTime.now();
        bill.createdAt = LocalDateTime.now();
        bill.rawPayload = truncate(request.rawHex(), 8000);

        ChargeOrder order = orders.findByOrderNo(request.transactionNo());
        if (order == null) {
            bill.stationId = device.stationId;
            bill.accepted = true;
            bill.rejectReason = "平台无对应订单，按离线账单入库";
        } else {
            bill.stationId = order.stationId;
            if (!Objects.equals(order.deviceId, device.id) || !Objects.equals(order.gunNo, request.gunNo())) {
                bill.accepted = false;
                bill.rejectReason = "订单桩号/枪号不匹配";
            } else {
                bill.orderId = order.id;
                bill.orderNo = order.orderNo;
                orders.settle(
                        order.orderNo,
                        request.totalEnergy(),
                        request.totalAmount(),
                        request.startTime(),
                        request.endTime(),
                        request.stopReason(),
                        ChargeOrder.AMOUNT_SOURCE_DEVICE);
            }
        }
        return bills.save(bill);
    }

    /** OCPP StopTransaction 的平台估算账单，与订单结算在同一事务内。 */
    @Transactional
    public Bill recordOcppSettlement(ChargeOrder order, String rateModelNo) {
        var existing =
                bills.findByProtocolAndSourceTypeAndSourceTransactionNo("OCPP16J", SOURCE_OCPP_STOP, order.orderNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        Bill bill = new Bill();
        bill.orderId = order.id;
        bill.orderNo = order.orderNo;
        bill.deviceId = order.deviceId;
        bill.stationId = order.stationId;
        bill.protocol = "OCPP16J";
        bill.sourceType = SOURCE_OCPP_STOP;
        bill.sourceTransactionNo = order.orderNo;
        bill.energy = order.energy;
        bill.amount = order.amount;
        bill.currency = "CNY";
        bill.amountType = ChargeOrder.AMOUNT_SOURCE_ESTIMATE;
        bill.stopReason = order.stopReason;
        bill.endedAt = order.endTime;
        bill.receivedAt = LocalDateTime.now();
        bill.createdAt = LocalDateTime.now();
        bill.accepted = true;
        bill.rawPayload =
                "{\"rateModelNo\":\"" + rateModelNo + "\",\"ocppTransactionId\":" + order.ocppTransactionId + "}";
        return bills.save(bill);
    }

    public List<Bill> search(
            Long stationId,
            Long deviceId,
            String protocol,
            String sourceTransactionNo,
            LocalDateTime from,
            LocalDateTime to,
            Boolean accepted) {
        Specification<Bill> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (stationId != null) predicates.add(cb.equal(root.get("stationId"), stationId));
            if (deviceId != null) predicates.add(cb.equal(root.get("deviceId"), deviceId));
            if (protocol != null && !protocol.isBlank()) predicates.add(cb.equal(root.get("protocol"), protocol));
            if (sourceTransactionNo != null && !sourceTransactionNo.isBlank()) {
                predicates.add(cb.equal(root.get("sourceTransactionNo"), sourceTransactionNo));
            }
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("endedAt"), from));
            if (to != null) predicates.add(cb.lessThan(root.get("endedAt"), to));
            if (accepted != null) predicates.add(cb.equal(root.get("accepted"), accepted));
            query.orderBy(cb.desc(root.get("id")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return bills.findAll(spec).stream().limit(500).toList();
    }

    public List<Bill> byOrder(Long orderId) {
        return bills.findAll((root, query, cb) -> {
            query.orderBy(cb.desc(root.get("id")));
            return cb.equal(root.get("orderId"), orderId);
        });
    }

    public OpsDtos.BillView view(Bill bill) {
        String code = null;
        try {
            code = devices.get(bill.deviceId).protocolDeviceId;
        } catch (RuntimeException ignored) {
        }
        return new OpsDtos.BillView(
                bill.id,
                bill.orderId,
                bill.orderNo,
                bill.deviceId,
                code,
                bill.stationId,
                bill.protocol,
                bill.sourceType,
                bill.sourceTransactionNo,
                bill.energy,
                bill.amount,
                bill.currency,
                bill.amountType,
                bill.stopReason,
                bill.endedAt,
                bill.receivedAt,
                bill.accepted,
                bill.rejectReason,
                bill.createdAt);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
