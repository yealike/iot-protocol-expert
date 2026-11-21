package top.yealike.pep.ops.charging.service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.pep.ops.api.dto.OpsDtos;
import top.yealike.pep.ops.charging.domain.ChargeOrder;
import top.yealike.pep.ops.charging.domain.OrderExternalReference;
import top.yealike.pep.ops.charging.mapper.ChargeOrderMapper;
import top.yealike.pep.ops.charging.mapper.OrderExternalReferenceMapper;
import top.yealike.pep.ops.devices.entity.DeviceInstance;
import top.yealike.pep.ops.devices.service.DeviceService;
import top.yealike.pep.ops.support.page.PageResult;

@Service
public class OrderService {

    public static final Set<ChargeOrder.Status> ACTIVE =
            Set.of(ChargeOrder.Status.PENDING, ChargeOrder.Status.CHARGING);
    public static final String YKC_TRANSACTION = "YKC_TRANSACTION";
    public static final String OCPP_TRANSACTION = "OCPP_TRANSACTION";

    private static final DateTimeFormatter PLATFORM_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS", Locale.ROOT);
    private static final DateTimeFormatter PROTOCOL_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ChargeOrderMapper orders;
    private final OrderExternalReferenceMapper references;
    private final DeviceService devices;
    private final ChargeBillingService billing;

    public OrderService(
            ChargeOrderMapper orders,
            OrderExternalReferenceMapper references,
            DeviceService devices,
            ChargeBillingService billing) {
        this.orders = orders;
        this.references = references;
        this.devices = devices;
        this.billing = billing;
    }

    public String nextOrderNo() {
        return LocalDateTime.now().format(PLATFORM_STAMP) + String.format(Locale.ROOT, "%04d", RANDOM.nextInt(10_000));
    }

    public String nextYkcTransactionNo(DeviceInstance device, String gunNo) {
        return device.deviceCode
                + gunNo
                + LocalDateTime.now().format(PROTOCOL_STAMP)
                + String.format(Locale.ROOT, "%08d", RANDOM.nextInt(100_000_000));
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
        if (!protocol.equals(device.protocol)) {
            throw new OrderConflictException("协议与设备产品不一致");
        }
        if (orders.findTopByDeviceIdAndGunNoAndStatusInOrderByCreatedAtDesc(deviceId, gunNo, ACTIVE)
                .isPresent()) {
            throw new OrderConflictException("枪 " + gunNo + " 已有进行中订单");
        }

        ChargeOrder order = new ChargeOrder();
        order.orderNo = nextOrderNo();
        order.deviceId = device.id;
        order.deviceSn = device.deviceCode;
        order.gunNo = gunNo;
        order.platformGunNo = device.deviceCode + gunNo;
        order.protocol = protocol;
        order.startType = startType;
        order.physicalCard = physicalCard;
        order.logicalCard = logicalCard;
        order.status = ChargeOrder.Status.PENDING;
        order.amountSource = ChargeOrder.AMOUNT_SOURCE_ESTIMATE;
        ChargeBillingService.RateSnapshot snapshot = billing.currentSnapshot();
        order.rateModelNo = snapshot.modelNo();
        order.rateSnapshot = billing.json(snapshot);
        order.createdAt = LocalDateTime.now();
        order.updatedAt = order.createdAt;
        try {
            orders.save(order);
        } catch (DataIntegrityViolationException exception) {
            throw new OrderConflictException("枪 " + gunNo + " 已有进行中订单");
        }
        billing.initializeOrder(order);
        if (!ocpp) {
            link(order, YKC_TRANSACTION, nextYkcTransactionNo(device, gunNo));
        }
        return order;
    }

    @Transactional
    public int bindOcppTransactionId(String orderNo, BigDecimal meterStartWh) {
        ChargeOrder order =
                orders.lockByOrderNo(orderNo).orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderNo));
        var existing = references.findByOrder(order.id, "OCPP16J", OCPP_TRANSACTION);
        if (existing.isPresent()) {
            return Integer.parseInt(existing.get().externalReference);
        }
        if (order.status != ChargeOrder.Status.PENDING) {
            throw new IllegalStateException("订单状态不允许启动交易: " + order.status);
        }
        int transactionId = Math.toIntExact(references.nextOcppTransactionId());
        order.meterStartWh = meterStartWh;
        order.updatedAt = LocalDateTime.now();
        orders.save(order);
        link(order, OCPP_TRANSACTION, String.valueOf(transactionId));
        return transactionId;
    }

    @Transactional
    public boolean applyMeter(String orderNo, BigDecimal energy, BigDecimal amount) {
        ChargeOrder order = orders.lockByOrderNo(orderNo).orElse(null);
        if (order == null || order.status == ChargeOrder.Status.SETTLED || order.status == ChargeOrder.Status.FAILED) {
            return false;
        }
        if (energy != null && energy.signum() >= 0 && (order.energy == null || energy.compareTo(order.energy) >= 0)) {
            order.energy = energy;
        }
        if (amount != null && amount.signum() >= 0 && (order.amount == null || amount.compareTo(order.amount) >= 0)) {
            order.amount = amount;
        }
        order.updatedAt = LocalDateTime.now();
        orders.save(order);
        return true;
    }

    /** 实时帧按设备、枪号和外部交易号定位订单，并在确认充电后推进状态。 */
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
        String referenceType = "YKC16".equals(protocol) ? YKC_TRANSACTION : OCPP_TRANSACTION;
        ChargeOrder order = transactionNo == null || transactionNo.isBlank()
                ? active(device.id, gunNo)
                : findByExternal(device.id, protocol, referenceType, transactionNo);
        if (order != null) {
            order = orders.lockByOrderNo(order.orderNo).orElse(null);
        }
        if (order == null || !Objects.equals(order.deviceId, device.id) || !Objects.equals(order.gunNo, gunNo)) {
            return;
        }
        if (order.status == ChargeOrder.Status.FAILED || order.status == ChargeOrder.Status.SETTLED) {
            return;
        }
        if (order.status == ChargeOrder.Status.PENDING && state == 3) {
            order.status = ChargeOrder.Status.CHARGING;
        }
        if (order.startTime == null && (state == 3 || order.status == ChargeOrder.Status.CHARGING)) {
            order.startTime = LocalDateTime.now();
        }
        if (energy != null && energy.signum() >= 0 && (order.energy == null || energy.compareTo(order.energy) >= 0)) {
            order.energy = energy;
        }
        if (amount != null && amount.signum() >= 0 && (order.amount == null || amount.compareTo(order.amount) >= 0)) {
            order.amount = amount;
        }
        order.updatedAt = LocalDateTime.now();
        orders.save(order);
    }

    @Transactional
    public void markCharging(String orderNo, LocalDateTime startTime) {
        orders.lockByOrderNo(orderNo).ifPresent(order -> {
            if (order.status != ChargeOrder.Status.PENDING && order.status != ChargeOrder.Status.CHARGING) {
                return;
            }
            order.status = ChargeOrder.Status.CHARGING;
            if (order.startTime == null) {
                order.startTime = startTime == null ? LocalDateTime.now() : startTime;
            }
            order.updatedAt = LocalDateTime.now();
            orders.save(order);
        });
    }

    @Transactional
    public void markChargingByTransaction(
            String protocol, String protocolDeviceId, String transactionNo, LocalDateTime startTime) {
        ChargeOrder order = requireExternal(protocol, protocolDeviceId, transactionNo);
        markCharging(order.orderNo, startTime);
    }

    @Transactional
    public void stop(String orderNo, int stopReason) {
        orders.lockByOrderNo(orderNo).ifPresent(order -> {
            if (order.status != ChargeOrder.Status.PENDING && order.status != ChargeOrder.Status.CHARGING) {
                return;
            }
            order.status = ChargeOrder.Status.STOPPED;
            if (order.endTime == null) {
                order.endTime = LocalDateTime.now();
            }
            order.stopReason = stopReason;
            order.updatedAt = LocalDateTime.now();
            orders.save(order);
        });
    }

    @Transactional
    public void fail(String orderNo, String reason) {
        orders.lockByOrderNo(orderNo).ifPresent(order -> failLocked(order, reason));
    }

    @Transactional
    public void failByTransaction(String protocol, String protocolDeviceId, String transactionNo, String reason) {
        ChargeOrder order = requireExternal(protocol, protocolDeviceId, transactionNo);
        orders.lockByOrderNo(order.orderNo).ifPresent(locked -> failStartLocked(locked, reason));
    }

    private void failLocked(ChargeOrder order, String reason) {
        if (order.status != ChargeOrder.Status.PENDING) {
            return;
        }
        markFailed(order, reason);
    }

    private void failStartLocked(ChargeOrder order, String reason) {
        if (order.status != ChargeOrder.Status.PENDING && order.status != ChargeOrder.Status.CHARGING) {
            return;
        }
        markFailed(order, reason);
    }

    private void markFailed(ChargeOrder order, String reason) {
        order.status = ChargeOrder.Status.FAILED;
        order.failReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 250));
        order.updatedAt = LocalDateTime.now();
        orders.save(order);
    }

    @Transactional
    public void settle(
            String orderNo,
            BigDecimal energy,
            BigDecimal electricityFee,
            BigDecimal serviceFee,
            BigDecimal amount,
            BigDecimal lossEnergy,
            LocalDateTime startTime,
            LocalDateTime endTime,
            int stopReason,
            String amountSource) {
        orders.lockByOrderNo(orderNo).ifPresent(order -> {
            boolean deviceBill = ChargeOrder.AMOUNT_SOURCE_DEVICE.equals(amountSource);
            if (order.status == ChargeOrder.Status.SETTLED
                    || (order.status == ChargeOrder.Status.FAILED && !deviceBill)) {
                return;
            }
            order.status = ChargeOrder.Status.SETTLED;
            if (energy != null && energy.signum() >= 0) {
                order.energy = energy;
            }
            if (electricityFee != null && electricityFee.signum() >= 0) {
                order.electricityFee = electricityFee;
            }
            if (serviceFee != null && serviceFee.signum() >= 0) {
                order.serviceFee = serviceFee;
            }
            if (amount != null && amount.signum() >= 0) {
                order.amount = amount;
            }
            if (lossEnergy != null && lossEnergy.signum() >= 0) {
                order.lossEnergy = lossEnergy;
            }
            if (startTime != null && order.startTime == null) {
                order.startTime = startTime;
            }
            order.endTime = endTime == null ? LocalDateTime.now() : endTime;
            order.stopReason = stopReason;
            order.amountSource = amountSource;
            if (deviceBill) {
                order.failReason = null;
            }
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
        settle(orderNo, energy, null, null, amount, null, startTime, endTime, stopReason, amountSource);
    }

    public ChargeOrder findByOrderNo(String orderNo) {
        return orders.findByOrderNo(orderNo).orElse(null);
    }

    public ChargeOrder lockByOrderNo(String orderNo) {
        return orders.lockByOrderNo(orderNo).orElse(null);
    }

    public ChargeOrder require(String orderNo) {
        return orders.findByOrderNo(orderNo).orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderNo));
    }

    public ChargeOrder active(Long deviceId, String gunNo) {
        return orders.findTopByDeviceIdAndGunNoAndStatusInOrderByCreatedAtDesc(deviceId, gunNo, ACTIVE)
                .orElse(null);
    }

    public ChargeOrder findByExternal(Long deviceId, String protocol, String referenceType, String externalReference) {
        return references
                .findByExternal(deviceId, protocol, referenceType, externalReference)
                .map(reference -> orders.findById(reference.orderId).orElse(null))
                .orElse(null);
    }

    public ChargeOrder requireExternal(String protocol, String protocolDeviceId, String transactionNo) {
        DeviceInstance device = devices.resolve(protocol, protocolDeviceId);
        String referenceType = "YKC16".equals(protocol) ? YKC_TRANSACTION : OCPP_TRANSACTION;
        ChargeOrder order = findByExternal(device.id, protocol, referenceType, transactionNo);
        if (order == null) {
            throw new IllegalArgumentException("交易号不存在: " + transactionNo);
        }
        return order;
    }

    public String transactionNo(ChargeOrder order) {
        String referenceType = "YKC16".equals(order.protocol) ? YKC_TRANSACTION : OCPP_TRANSACTION;
        return references
                .findByOrder(order.id, order.protocol, referenceType)
                .map(reference -> reference.externalReference)
                .orElse(null);
    }

    public ChargeOrder byOcppTransactionId(Long deviceId, Integer transactionId) {
        return transactionId == null
                ? null
                : findByExternal(deviceId, "OCPP16J", OCPP_TRANSACTION, String.valueOf(transactionId));
    }

    public ChargeOrder lockOcppTransactionId(Long deviceId, Integer transactionId) {
        ChargeOrder order = byOcppTransactionId(deviceId, transactionId);
        return order == null ? null : lockByOrderNo(order.orderNo);
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
        for (ChargeOrder order : orders.findPendingBefore(cutoff, 200)) {
            ChargeOrder locked = orders.lockByOrderNo(order.orderNo).orElse(null);
            if (locked != null
                    && locked.status == ChargeOrder.Status.PENDING
                    && locked.createdAt != null
                    && locked.createdAt.isBefore(cutoff)) {
                locked.status = ChargeOrder.Status.FAILED;
                locked.failReason = "90秒内未完成启动";
                locked.updatedAt = LocalDateTime.now();
                orders.save(locked);
            }
        }
    }

    public List<ChargeOrder> search(
            Long deviceId,
            String gunNo,
            String protocol,
            String orderNo,
            LocalDateTime from,
            LocalDateTime to,
            Collection<ChargeOrder.Status> statuses) {
        return searchPage(deviceId, gunNo, protocol, orderNo, from, to, statuses, 0, 500)
                .content();
    }

    public PageResult<ChargeOrder> searchPage(
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
        List<ChargeOrder> content =
                orders.search(deviceId, gunNo, protocol, orderNo, from, to, statuses, pageSize, (int) offset);
        long total = orders.countSearch(deviceId, gunNo, protocol, orderNo, from, to, statuses);
        return PageResult.of(content, pageIndex, pageSize, total);
    }

    public OpsDtos.OrderView view(ChargeOrder order) {
        DeviceInstance device = null;
        try {
            device = devices.get(order.deviceId);
        } catch (RuntimeException ignored) {
            // The order keeps its SN snapshot even if the device record was removed.
        }
        String deviceSn = order.deviceSn != null ? order.deviceSn : device == null ? null : device.deviceCode;
        String platformGunNo =
                order.platformGunNo != null ? order.platformGunNo : deviceSn == null ? null : deviceSn + order.gunNo;
        return new OpsDtos.OrderView(
                order.id,
                order.orderNo,
                transactionNo(order),
                order.deviceId,
                deviceSn,
                order.gunNo,
                platformGunNo,
                order.protocol,
                order.status.name(),
                order.meterStartWh,
                order.startTime,
                order.endTime,
                durationSeconds(order),
                order.energy,
                order.electricityFee,
                order.serviceFee,
                order.amount,
                order.lossEnergy,
                order.amountSource,
                order.rateModelNo,
                order.rateSnapshot,
                order.stopReason,
                order.failReason,
                order.createdAt);
    }

    private static Long durationSeconds(ChargeOrder order) {
        if (order.startTime == null) {
            return null;
        }
        LocalDateTime end = order.endTime;
        if (end == null
                && (order.status == ChargeOrder.Status.PENDING || order.status == ChargeOrder.Status.CHARGING)) {
            end = LocalDateTime.now();
        }
        if (end == null) {
            return null;
        }
        return Math.max(0, Duration.between(order.startTime, end).getSeconds());
    }

    public boolean sameGun(String orderNo, Long deviceId, String gunNo) {
        ChargeOrder order = findByOrderNo(orderNo);
        return order != null && Objects.equals(order.deviceId, deviceId) && Objects.equals(order.gunNo, gunNo);
    }

    private void link(ChargeOrder order, String referenceType, String externalReference) {
        OrderExternalReference reference = new OrderExternalReference();
        reference.orderId = order.id;
        reference.deviceId = order.deviceId;
        reference.protocol = order.protocol;
        reference.referenceType = referenceType;
        reference.externalReference = externalReference;
        reference.createdAt = LocalDateTime.now();
        references.save(reference);
    }

    public static class OrderConflictException extends RuntimeException {
        public OrderConflictException(String message) {
            super(message);
        }
    }
}
