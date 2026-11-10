package top.yealike.ipe.protocol.service;

import top.yealike.ipe.protocol.codec.frames.OperationFrames;
import top.yealike.ipe.protocol.domain.ChargeOrderEntity;
import top.yealike.ipe.protocol.domain.TransactionEntity;
import top.yealike.ipe.protocol.repository.TransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 交易记录接收与结算。 */
@Service
public class TransactionService {

    private final TransactionRepository repository;
    private final OrderService orderService;

    public TransactionService(TransactionRepository repository, OrderService orderService) {
        this.repository = repository;
        this.orderService = orderService;
    }

    /**
     * 接收交易记录。重复上送按幂等处理；找不到订单时仍入库并标记为离线账单。
     */
    @Transactional
    public TransactionEntity accept(OperationFrames.Transaction tx, String rawHex) {
        List<TransactionEntity> existing = repository.findByTransactionNoOrderByReceivedAtDesc(tx.transactionNo());
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        TransactionEntity entity = new TransactionEntity();
        entity.setTransactionNo(tx.transactionNo());
        entity.setPileCode(tx.pileCode());
        entity.setGunNo(tx.gunNo());
        entity.setStartTime(tx.startTime());
        entity.setEndTime(tx.endTime());
        entity.setTradeTime(tx.tradeTime());
        entity.setTotalEnergy(tx.totalEnergy());
        entity.setTotalAmount(tx.totalAmount());
        entity.setLossTotalEnergy(tx.lossTotalEnergy());
        entity.setStopReason(tx.stopReason());
        entity.setTradeFlag(tx.tradeFlag());
        entity.setPhysicalCard(tx.physicalCard());
        entity.setVin(tx.vin());
        entity.setRawHex(rawHex);
        entity.setReceivedAt(LocalDateTime.now());
        entity.setAccepted(true);

        ChargeOrderEntity order = orderService.findByTransactionNo(tx.transactionNo());
        if (order == null) {
            entity.setRejectReason("平台无对应订单，按离线账单入库");
        } else if (!order.getPileCode().equals(tx.pileCode()) || !order.getGunNo().equals(tx.gunNo())) {
            entity.setRejectReason("订单桩号/枪号不匹配");
            entity.setAccepted(false);
        } else {
            orderService.settle(tx.transactionNo(), tx.totalEnergy(), tx.totalAmount(),
                    tx.startTime(), tx.endTime(), tx.stopReason());
        }
        return repository.save(entity);
    }

    public List<TransactionEntity> recent(int limit) {
        return repository.findAllByOrderByReceivedAtDesc(PageRequest.of(0, Math.min(Math.max(limit, 1), 500)));
    }

    public List<TransactionEntity> byTransactionNo(String transactionNo) {
        return repository.findByTransactionNoOrderByReceivedAtDesc(transactionNo);
    }
}