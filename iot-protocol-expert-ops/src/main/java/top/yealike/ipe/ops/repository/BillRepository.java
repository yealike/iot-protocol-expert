package top.yealike.ipe.ops.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import top.yealike.ipe.ops.domain.Bill;

public interface BillRepository extends JpaRepository<Bill, Long>, JpaSpecificationExecutor<Bill> {
    Optional<Bill> findByProtocolAndSourceTypeAndSourceTransactionNo(
            String protocol, String sourceType, String sourceTransactionNo);

    long countByOrderId(Long orderId);

    interface BillSummary {
        Long getC();

        BigDecimal getA();
    }

    @Query(
            "select count(b) as c, coalesce(sum(b.amount), 0) as a from Bill b "
                    + "where b.stationId = ?1 and b.accepted = true and b.amountType = ?2 and b.endedAt >= ?3 and b.endedAt < ?4")
    BillSummary summarizeStation(Long stationId, String amountType, LocalDateTime from, LocalDateTime to);

    long countByStationIdIsNull();
}
