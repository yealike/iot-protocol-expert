package top.yealike.pep.ops.charging.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.ops.charging.domain.Bill;

public interface BillMapper {

    Bill selectById(@Param("id") Long id);

    Bill selectBySource(
            @Param("deviceId") Long deviceId,
            @Param("protocol") String protocol,
            @Param("sourceType") String sourceType,
            @Param("sourceTransactionNo") String sourceTransactionNo);

    List<Bill> search(
            @Param("deviceId") Long deviceId,
            @Param("protocol") String protocol,
            @Param("sourceTransactionNo") String sourceTransactionNo,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("accepted") Boolean accepted,
            @Param("limit") int limit);

    List<Bill> selectByOrderId(@Param("orderId") Long orderId);

    long countByOrderId(@Param("orderId") Long orderId);

    int lockSourceTransaction(
            @Param("deviceId") Long deviceId,
            @Param("protocol") String protocol,
            @Param("sourceType") String sourceType,
            @Param("sourceTransactionNo") String sourceTransactionNo);

    int insert(Bill bill);

    int update(Bill bill);

    default Optional<Bill> findByDeviceAndProtocolAndSourceTypeAndSourceTransactionNo(
            Long deviceId, String protocol, String sourceType, String sourceTransactionNo) {
        return Optional.ofNullable(selectBySource(deviceId, protocol, sourceType, sourceTransactionNo));
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
