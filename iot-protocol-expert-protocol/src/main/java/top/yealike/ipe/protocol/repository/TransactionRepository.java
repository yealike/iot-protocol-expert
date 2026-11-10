package top.yealike.ipe.protocol.repository;

import top.yealike.ipe.protocol.domain.TransactionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionRepository extends JpaRepository<TransactionEntity, Long> {

    List<TransactionEntity> findAllByOrderByReceivedAtDesc(Pageable pageable);

    List<TransactionEntity> findByTransactionNoOrderByReceivedAtDesc(String transactionNo);
}