package top.yealike.ipe.ops.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import top.yealike.ipe.ops.domain.ChargeOrder;

public interface ChargeOrderRepository extends JpaRepository<ChargeOrder, Long>, JpaSpecificationExecutor<ChargeOrder> {
    Optional<ChargeOrder> findByOrderNo(String orderNo);

    Optional<ChargeOrder> findTopByDeviceIdAndGunNoAndStatusInOrderByCreatedAtDesc(
            Long deviceId, String gunNo, Collection<ChargeOrder.Status> statuses);

    Optional<ChargeOrder> findTopByOcppTransactionIdAndProtocolOrderByCreatedAtDesc(
            Integer ocppTransactionId, String protocol);

    List<ChargeOrder> findTop200ByOrderByIdDesc();

    java.util.Optional<ChargeOrder> findTopByDeviceIdOrderByIdDesc(Long deviceId);

    @Query("select coalesce(max(o.ocppTransactionId), 0) from ChargeOrder o where o.protocol = 'OCPP16J'")
    long maxOcppTransactionId();

    long countByStatusIn(Collection<ChargeOrder.Status> statuses);

    interface OrderSummary {
        Long getC();

        BigDecimal getE();

        BigDecimal getA();
    }

    @Query("select count(o) as c, coalesce(sum(o.energy), 0) as e, coalesce(sum(o.amount), 0) as a "
            + "from ChargeOrder o where o.stationId = ?1 and o.status = ?2 and o.endTime >= ?3 and o.endTime < ?4")
    OrderSummary summarizeStation(Long stationId, ChargeOrder.Status status, LocalDateTime from, LocalDateTime to);

    @Query("select count(o) as c, coalesce(sum(o.energy), 0) as e, coalesce(sum(o.amount), 0) as a "
            + "from ChargeOrder o where o.deviceId = ?1 and o.status = ?2 and o.endTime >= ?3 and o.endTime < ?4")
    OrderSummary summarizeDevice(Long deviceId, ChargeOrder.Status status, LocalDateTime from, LocalDateTime to);
}
