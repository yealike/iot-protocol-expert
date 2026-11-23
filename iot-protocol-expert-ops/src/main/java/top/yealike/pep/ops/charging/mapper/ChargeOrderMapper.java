package top.yealike.pep.ops.charging.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.ops.charging.domain.ChargeOrder;
import top.yealike.pep.ops.charging.domain.OrderSummary;

public interface ChargeOrderMapper {

    ChargeOrder selectById(@Param("id") Long id);

    ChargeOrder selectByOrderNo(@Param("orderNo") String orderNo);

    ChargeOrder selectByOrderNoForUpdate(@Param("orderNo") String orderNo);

    ChargeOrder selectTopActive(
            @Param("deviceId") Long deviceId,
            @Param("gunNo") String gunNo,
            @Param("statuses") Collection<ChargeOrder.Status> statuses);

    ChargeOrder selectTopByDevice(@Param("deviceId") Long deviceId);

    List<ChargeOrder> selectTop200();

    List<ChargeOrder> selectPendingBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    List<ChargeOrder> search(
            @Param("deviceId") Long deviceId,
            @Param("gunNo") String gunNo,
            @Param("protocol") String protocol,
            @Param("orderNo") String orderNo,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("statuses") Collection<ChargeOrder.Status> statuses,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countSearch(
            @Param("deviceId") Long deviceId,
            @Param("gunNo") String gunNo,
            @Param("protocol") String protocol,
            @Param("orderNo") String orderNo,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("statuses") Collection<ChargeOrder.Status> statuses);

    long countByStatusIn(@Param("statuses") Collection<ChargeOrder.Status> statuses);

    OrderSummary summarizeDevice(
            @Param("deviceId") Long deviceId,
            @Param("status") ChargeOrder.Status status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    OrderSummary summarizeAll(
            @Param("status") ChargeOrder.Status status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    BigDecimal sumEnergyActiveBetween(
            @Param("exclusiveEnd") LocalDateTime exclusiveEnd, @Param("start") LocalDateTime start);

    int insert(ChargeOrder order);

    int update(ChargeOrder order);

    default Optional<ChargeOrder> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default Optional<ChargeOrder> findByOrderNo(String orderNo) {
        return Optional.ofNullable(selectByOrderNo(orderNo));
    }

    default Optional<ChargeOrder> lockByOrderNo(String orderNo) {
        return Optional.ofNullable(selectByOrderNoForUpdate(orderNo));
    }

    default Optional<ChargeOrder> findTopByDeviceIdAndGunNoAndStatusInOrderByCreatedAtDesc(
            Long deviceId, String gunNo, Collection<ChargeOrder.Status> statuses) {
        return statuses == null || statuses.isEmpty()
                ? Optional.empty()
                : Optional.ofNullable(selectTopActive(deviceId, gunNo, statuses));
    }

    default Optional<ChargeOrder> findTopByDeviceIdOrderByIdDesc(Long deviceId) {
        return Optional.ofNullable(selectTopByDevice(deviceId));
    }

    default List<ChargeOrder> findTop200ByOrderByIdDesc() {
        return selectTop200();
    }

    default List<ChargeOrder> findPendingBefore(LocalDateTime cutoff, int limit) {
        return selectPendingBefore(cutoff, limit);
    }

    default ChargeOrder save(ChargeOrder order) {
        if (order.id == null) {
            insert(order);
        } else {
            update(order);
        }
        return order;
    }
}
