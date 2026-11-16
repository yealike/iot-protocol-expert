package top.yealike.ipe.ops.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import top.yealike.ipe.ops.domain.Bill;

public interface BillMapper {

    record BillSummary(long c, BigDecimal a) {
        public Long getC() {
            return c;
        }

        public BigDecimal getA() {
            return a;
        }
    }

    Bill selectById(@Param("id") Long id);

    Bill selectBySource(
            @Param("protocol") String protocol,
            @Param("sourceType") String sourceType,
            @Param("sourceTransactionNo") String sourceTransactionNo);

    List<Bill> search(
            @Param("stationId") Long stationId,
            @Param("deviceId") Long deviceId,
            @Param("protocol") String protocol,
            @Param("sourceTransactionNo") String sourceTransactionNo,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("accepted") Boolean accepted,
            @Param("limit") int limit);

    List<Bill> selectByOrderId(@Param("orderId") Long orderId);

    long countByOrderId(@Param("orderId") Long orderId);

    BillSummary summarizeStation(
            @Param("stationId") Long stationId,
            @Param("amountType") String amountType,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    long countByStationIdIsNull();

    int insert(Bill bill);

    int update(Bill bill);

    default Optional<Bill> findByProtocolAndSourceTypeAndSourceTransactionNo(
            String protocol, String sourceType, String sourceTransactionNo) {
        return Optional.ofNullable(selectBySource(protocol, sourceType, sourceTransactionNo));
    }

    default List<Bill> findAllByOrderId(Long orderId) {
        return selectByOrderId(orderId);
    }

    default Bill save(Bill bill) {
        if (bill.id == null) {
            insert(bill);
        } else {
            update(bill);
        }
        return bill;
    }
}
