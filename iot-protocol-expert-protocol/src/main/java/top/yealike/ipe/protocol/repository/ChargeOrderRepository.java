package top.yealike.ipe.protocol.repository;

import top.yealike.ipe.protocol.domain.ChargeOrderEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChargeOrderRepository extends JpaRepository<ChargeOrderEntity, Long> {

    Optional<ChargeOrderEntity> findByTransactionNo(String transactionNo);

    Optional<ChargeOrderEntity> findTopByPileCodeAndGunNoAndStatusInOrderByCreatedAtDesc(
            String pileCode, String gunNo, Collection<ChargeOrderEntity.Status> statuses);

    List<ChargeOrderEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatusIn(Collection<ChargeOrderEntity.Status> statuses);
}