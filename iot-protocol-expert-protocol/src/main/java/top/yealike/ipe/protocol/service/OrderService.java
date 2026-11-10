package top.yealike.ipe.protocol.service;

import top.yealike.ipe.protocol.codec.frames.RealtimeFrames;
import top.yealike.ipe.protocol.domain.ChargeOrderEntity;
import top.yealike.ipe.protocol.repository.ChargeOrderRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 订单生命周期。 */
@Service
public class OrderService {

    private static final Set<ChargeOrderEntity.Status> ACTIVE = Set.of(
            ChargeOrderEntity.Status.PENDING, ChargeOrderEntity.Status.CHARGING);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    private final ChargeOrderRepository repository;
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicReference<String> sequenceStamp = new AtomicReference<>("");

    public OrderService(ChargeOrderRepository repository) {
        this.repository = repository;
    }

    /** 交易流水号：桩号 14 位 + 枪号 2 位 + yyMMddHHmmss + 4 位自增序号，共 32 位。 */
    public synchronized String nextTransactionNo(String pileCode, String gunNo) {
        String stamp = LocalDateTime.now().format(STAMP);
        if (!stamp.equals(sequenceStamp.get())) {
            sequenceStamp.set(stamp);
            sequence.set(0);
        }
        int seq = sequence.incrementAndGet() % 10000;
        return pileCode + gunNo + stamp + String.format("%04d", seq);
    }

    @Transactional
    public ChargeOrderEntity create(String pileCode, String gunNo, int startType,
                                    String physicalCard, String logicalCard) {
        repository.findTopByPileCodeAndGunNoAndStatusInOrderByCreatedAtDesc(pileCode, gunNo, ACTIVE)
                .ifPresent(existing -> {
                    throw new OrderConflictException("枪 " + gunNo + " 已有进行中订单: " + existing.getTransactionNo());
                });
        ChargeOrderEntity order = new ChargeOrderEntity();
        order.setTransactionNo(nextTransactionNo(pileCode, gunNo));
        order.setPileCode(pileCode);
        order.setGunNo(gunNo);
        order.setStartType(startType);
        order.setPhysicalCard(physicalCard);
        order.setLogicalCard(logicalCard);
        order.setStatus(ChargeOrderEntity.Status.PENDING);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        return repository.save(order);
    }

    @Transactional
    public void applyRealtime(RealtimeFrames.Realtime realtime) {
        if (realtime.transactionNo() == null || realtime.transactionNo().isBlank()) {
            return;
        }
        repository.findByTransactionNo(realtime.transactionNo()).ifPresent(order -> {
            if(!Objects.equals(order.getPileCode(),realtime.pileCode())||!Objects.equals(order.getGunNo(),realtime.gunNo()))throw new IllegalArgumentException("实时帧流水号与桩枪不符");
            if (order.getStatus() == ChargeOrderEntity.Status.FAILED
                    || order.getStatus() == ChargeOrderEntity.Status.SETTLED) {
                return;
            }
            if (order.getStatus() == ChargeOrderEntity.Status.PENDING && realtime.state()==3) {
                order.setStatus(ChargeOrderEntity.Status.CHARGING);
            }
            if (order.getStartTime() == null) {
                order.setStartTime(LocalDateTime.now());
            }
            order.setEnergy(realtime.chargedEnergy());
            order.setAmount(realtime.chargedAmount());
            order.setUpdatedAt(LocalDateTime.now());
            repository.save(order);
        });
    }

    @Transactional
    public void markCharging(String transactionNo) {
        repository.findByTransactionNo(transactionNo).ifPresent(order -> {
            if (order.getStatus()==ChargeOrderEntity.Status.SETTLED || order.getStatus()==ChargeOrderEntity.Status.STOPPED) return;
            order.setStatus(ChargeOrderEntity.Status.CHARGING);
            if (order.getStartTime() == null) {
                order.setStartTime(LocalDateTime.now());
            }
            order.setUpdatedAt(LocalDateTime.now());
            repository.save(order);
        });
    }

    @Transactional
    public void stop(String transactionNo, int stopReason) {
        repository.findByTransactionNo(transactionNo).ifPresent(order -> {
            if (order.getStatus()==ChargeOrderEntity.Status.SETTLED) return;
            order.setStatus(ChargeOrderEntity.Status.STOPPED);
            order.setEndTime(LocalDateTime.now());
            order.setStopReason(stopReason);
            order.setUpdatedAt(LocalDateTime.now());
            repository.save(order);
        });
    }

    @Transactional
    public void fail(String transactionNo, String reason) {
        repository.findByTransactionNo(transactionNo).ifPresent(order -> {
            order.setStatus(ChargeOrderEntity.Status.FAILED);
            order.setFailReason(reason);
            order.setUpdatedAt(LocalDateTime.now());
            repository.save(order);
        });
    }

    @Transactional
    public void settle(String transactionNo, BigDecimal energy, BigDecimal amount,
                       LocalDateTime startTime, LocalDateTime endTime, int stopReason) {
        repository.findByTransactionNo(transactionNo).ifPresent(order -> {
            order.setStatus(ChargeOrderEntity.Status.SETTLED);
            if (energy != null && energy.signum() > 0) {
                order.setEnergy(energy);
            }
            if (amount != null && amount.signum() > 0) {
                order.setAmount(amount);
            }
            if (startTime != null && order.getStartTime() == null) {
                order.setStartTime(startTime);
            }
            order.setEndTime(endTime == null ? LocalDateTime.now() : endTime);
            order.setStopReason(stopReason);
            order.setUpdatedAt(LocalDateTime.now());
            repository.save(order);
        });
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=10000)
    public void expirePending() {
        LocalDateTime cutoff=LocalDateTime.now().minusSeconds(90);
        for(var order:recent(500))if(order.getStatus()==ChargeOrderEntity.Status.PENDING && order.getCreatedAt().isBefore(cutoff))fail(order.getTransactionNo(),"90秒内未完成启动");
    }
    public ChargeOrderEntity findByTransactionNo(String transactionNo) {
        return repository.findByTransactionNo(transactionNo).orElse(null);
    }

    public ChargeOrderEntity active(String pileCode, String gunNo) {
        return repository.findTopByPileCodeAndGunNoAndStatusInOrderByCreatedAtDesc(pileCode, gunNo, ACTIVE)
                .orElse(null);
    }

    public List<ChargeOrderEntity> recent(int limit) {
        return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.min(Math.max(limit, 1), 500)));
    }

    public long runningCount() {
        return repository.countByStatusIn(ACTIVE);
    }

    public boolean sameGun(String pileCode, String gunNo, String transactionNo) {
        ChargeOrderEntity order = findByTransactionNo(transactionNo);
        return order != null
                && Objects.equals(order.getPileCode(), pileCode)
                && Objects.equals(order.getGunNo(), gunNo);
    }

    public static class OrderConflictException extends RuntimeException {
        public OrderConflictException(String message) {
            super(message);
        }
    }
}