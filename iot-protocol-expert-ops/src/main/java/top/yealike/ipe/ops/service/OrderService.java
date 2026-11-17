package top.yealike.ipe.ops.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.ipe.ops.common.PageResult;
import top.yealike.ipe.ops.device.entity.DeviceInstance;
import top.yealike.ipe.ops.domain.ChargeOrder;
import top.yealike.ipe.ops.dto.OpsDtos;
import top.yealike.ipe.ops.mapper.ChargeOrderMapper;

@Service
public class OrderService {

    public static final Set<ChargeOrder.Status> ACTIVE =
            Set.of(ChargeOrder.Status.PENDING, ChargeOrder.Status.CHARGING);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    private final ChargeOrderMapper orders;
    private final DeviceService devices;
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile String sequenceStamp = "";

    public OrderService(ChargeOrderMapper orders, DeviceService devices) {
        this.orders = orders;
        this.devices = devices;
    }

    public synchronized String nextOrderNo(DeviceInstance device, String gunNo) {
        String stamp = LocalDateTime.now().format(STAMP);
        if (!stamp.equals(sequenceStamp)) {
            sequenceStamp = stamp;
            sequence.set(0);
        }
        String prefix = device.protocolDeviceId.length() >= 14
                ? device.protocolDeviceId.substring(0, 14)
                : (device.protocolDeviceId + "00000000000000").substring(0, 14);
        return prefix + gunNo + stamp + String.format("%04d", sequence.incrementAndGet() % 10000);
    }

    @Transactional
    public ChargeOrder create(
            Long deviceId,
            String gunNo,
            int startType,
            String physicalCard,
            String logicalCard,
            String protocol,
            boolean ocpp) {
        DeviceInstance device = devices.get(deviceId);
        if (!device.enabled || !device.allowWork) {
            throw new OrderConflictException("设备已停用，拒绝建单");
        }
        if (orders.findTopByDeviceIdAndGunNoAndStatusInOrderByCreatedAtDesc(deviceId, gunNo, ACTIVE)
                .isPresent()) {
            throw new OrderConflictException("枪 " + gunNo + " 已有进行中订单");
        }
        ChargeOrder order = new ChargeOrder();
        order.orderNo = nextOrderNo(device, gunNo);
        order.deviceId = device.id;
        order.gunNo = gunNo;
        order.stationId = device.stationId;
        order.protocol = protocol;
        order.protocolTransactionId = ocpp ? null : order.orderNo;
        order.startType = startType;
        order.physicalCard = physicalCard;
        order.logicalCard = logicalCard;
        order.status = ChargeOrder.Status.PENDING;
        order.amountSource = ChargeOrder.AMOUNT_SOURCE_ESTIMATE;
        order.createdAt = LocalDateTime.now();
        order.updatedAt = LocalDateTime.now();
        return orders.save(order);
    }

    @Transactional
    public int bindOcppTransactionId(String orderNo, BigDecimal meterStartWh) {
        ChargeOrder order = require(orderNo);
        if (order.ocppTransactionId == null) {
            order.ocppTransactionId = Math.toIntExact(orders.maxOcppTransactionId() + 1);
            order.protocolTransactionId = String.valueOf(order.ocppTransactionId);
            order.meterStartWh = meterStartWh;
            order.updatedAt = LocalDateTime.now();
            orders.save(order);
        }
        return order.ocppTransactionId;
    }

    @Transactional
    public void applyMeter(String orderNo, BigDecimal energy, BigDecimal amount) {
        orders.findByOrderNo(orderNo).ifPresent(order -> {
            if (order.status == ChargeOrder.Status.SETTLED || order.status == ChargeOrder.Status.FAILED) {
                return;
            }
            if (energy != null && energy.signum() >= 0) order.energy = energy;
            if (amount != null && amount.signum() >= 0) order.amount = amount;
            order.updatedAt = LocalDateTime.now();
            orders.save(order);
        });
    }

    /** 实时帧驱动订单进入充电态：0x31 启动路径没有单独的确认，以实时状态为准。 */
    @Transactional
    public void applyRealtime(
            String protocol,
            String protocolDeviceId,
            String gunNo,
            int state,
            BigDecimal energy,
            BigDecimal amount,
            String transactionNo) {
        DeviceInstance device = devices.resolve(protocol, protocolDeviceId);
        ChargeOrder order = transactionNo != null && !transactionNo.isBlank()
                ? findByOrderNo(transactionNo)
                : active(device.id, gunNo);
        if (order == null
                || !java.util.Objects.equals(order.deviceId, device.id)
                || !java.util.Objects.equals(order.gunNo, gunNo)) {
            return;
        }
        if (order.status == ChargeOrder.Status.FAILED || order.status == ChargeOrder.Status.SETTLED) {
            return;
        }
        if (order.status == ChargeOrder.Status.PENDING && state == 3) {
            order.status = ChargeOrder.Status.CHARGING;
        }
        if (order.startTime == null) {
            order.startTime = LocalDateTime.now();
        }
        if (energy != null && energy.signum() >= 0) order.energy = energy;
        if (amount != null && amount.signum() >= 0) order.amount = amount;
        order.updatedAt = LocalDateTime.now();
        orders.save(order);
    }

    @Transactional
    public void markCharging(String orderNo, LocalDateTime startTime) {
        orders.findByOrderNo(orderNo).ifPresent(order -> {
            if (order.status == ChargeOrder.Status.SETTLED || order.status == ChargeOrder.Status.STOPPED) return;
            order.status = ChargeOrder.Status.CHARGING;
            if (order.startTime == null) {
                order.startTime = startTime == null ? LocalDateTime.now() : startTime;
            }
            order.updatedAt = LocalDateTime.now();
            orders.save(order);
        });
    }

    @Transactional
    public void stop(String orderNo, int stopReason) {
        orders.findByOrderNo(orderNo).ifPresent(order -> {
            if (order.status == ChargeOrder.Status.SETTLED) return;
            order.status = ChargeOrder.Status.STOPPED;
            if (order.endTime == null) order.endTime = LocalDateTime.now();
            order.stopReason = stopReason;
            order.updatedAt = LocalDateTime.now();
            orders.save(order);
        });
    }

    @Transactional
    public void fail(String orderNo, String reason) {
        orders.findByOrderNo(orderNo).ifPresent(order -> {
            order.status = ChargeOrder.Status.FAILED;
            order.failReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 250));
            order.updatedAt = LocalDateTime.now();
            orders.save(order);
        });
    }

    @Transactional
    public void settle(
            String orderNo,
            BigDecimal energy,
            BigDecimal amount,
            LocalDateTime startTime,
            LocalDateTime endTime,
            int stopReason,
            String amountSource) {
        orders.findByOrderNo(orderNo).ifPresent(order -> {
            order.status = ChargeOrder.Status.SETTLED;
            if (energy != null && energy.signum() >= 0) order.energy = energy;
            if (amount != null && amount.signum() >= 0) order.amount = amount;
            if (startTime != null && order.startTime == null) order.startTime = startTime;
            order.endTime = endTime == null ? LocalDateTime.now() : endTime;
            order.stopReason = stopReason;
            order.amountSource = amountSource;
            order.updatedAt = LocalDateTime.now();
            orders.save(order);
        });
    }

    public ChargeOrder findByOrderNo(String orderNo) {
        return orders.findByOrderNo(orderNo).orElse(null);
    }

    public ChargeOrder require(String orderNo) {
        return orders.findByOrderNo(orderNo).orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderNo));
    }

    public ChargeOrder active(Long deviceId, String gunNo) {
        return orders.findTopByDeviceIdAndGunNoAndStatusInOrderByCreatedAtDesc(deviceId, gunNo, ACTIVE)
                .orElse(null);
    }

    public ChargeOrder byOcppTransactionId(Integer transactionId) {
        return transactionId == null
                ? null
                : orders.findTopByOcppTransactionIdAndProtocolOrderByCreatedAtDesc(transactionId, "OCPP16J")
                        .orElse(null);
    }

    public List<ChargeOrder> recent() {
        return orders.findTop200ByOrderByIdDesc();
    }

    public long runningCount() {
        return orders.countByStatusIn(ACTIVE);
    }

    @Scheduled(fixedDelay = 10_000)
    @Transactional
    public void expirePending() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(90);
        for (ChargeOrder order : orders.findTop200ByOrderByIdDesc()) {
            if (order.status == ChargeOrder.Status.PENDING
                    && order.createdAt != null
                    && order.createdAt.isBefore(cutoff)) {
                order.status = ChargeOrder.Status.FAILED;
                order.failReason = "90秒内未完成启动";
                order.updatedAt = LocalDateTime.now();
                orders.save(order);
            }
        }
    }

    public List<ChargeOrder> search(
            Long stationId,
            Long deviceId,
            String gunNo,
            String protocol,
            String orderNo,
            LocalDateTime from,
            LocalDateTime to,
            Collection<ChargeOrder.Status> statuses) {
        return searchPage(stationId, deviceId, gunNo, protocol, orderNo, from, to, statuses, 0, 500)
                .content();
    }

    public PageResult<ChargeOrder> searchPage(
            Long stationId,
            Long deviceId,
            String gunNo,
            String protocol,
            String orderNo,
            LocalDateTime from,
            LocalDateTime to,
            Collection<ChargeOrder.Status> statuses,
            int page,
            int size) {
        int pageIndex = Math.max(0, page);
        int pageSize = Math.max(1, Math.min(size, 500));
        long offset = (long) pageIndex * pageSize;
        if (offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("页码超出范围");
        }
        List<ChargeOrder> content = orders.search(
                stationId, deviceId, gunNo, protocol, orderNo, from, to, statuses, pageSize, (int) offset);
        long total = orders.countSearch(stationId, deviceId, gunNo, protocol, orderNo, from, to, statuses);
        return PageResult.of(content, pageIndex, pageSize, total);
    }

    public OpsDtos.OrderView view(ChargeOrder order) {
        return new OpsDtos.OrderView(
                order.id,
                order.orderNo,
                order.deviceId,
                protocolDeviceId(order),
                order.gunNo,
                order.stationId,
                order.protocol,
                order.status.name(),
                order.ocppTransactionId,
                order.meterStartWh,
                order.startTime,
                order.endTime,
                order.energy,
                order.amount,
                order.amountSource,
                order.stopReason,
                order.failReason,
                order.createdAt);
    }

    private String protocolDeviceId(ChargeOrder order) {
        return devices.get(order.deviceId).protocolDeviceId;
    }

    public boolean sameGun(String orderNo, Long deviceId, String gunNo) {
        ChargeOrder order = findByOrderNo(orderNo);
        return order != null && Objects.equals(order.deviceId, deviceId) && Objects.equals(order.gunNo, gunNo);
    }

    public static class OrderConflictException extends RuntimeException {
        public OrderConflictException(String message) {
            super(message);
        }
    }
}
