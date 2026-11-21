package top.yealike.pep.ops.charging.mapper;

import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.ops.charging.domain.OrderExternalReference;

public interface OrderExternalReferenceMapper {

    int insert(OrderExternalReference reference);

    OrderExternalReference selectByExternal(
            @Param("deviceId") Long deviceId,
            @Param("protocol") String protocol,
            @Param("referenceType") String referenceType,
            @Param("externalReference") String externalReference);

    OrderExternalReference selectByOrder(
            @Param("orderId") Long orderId,
            @Param("protocol") String protocol,
            @Param("referenceType") String referenceType);

    Long nextOcppTransactionId();

    default Optional<OrderExternalReference> findByExternal(
            Long deviceId, String protocol, String referenceType, String externalReference) {
        return Optional.ofNullable(selectByExternal(deviceId, protocol, referenceType, externalReference));
    }

    default Optional<OrderExternalReference> findByOrder(Long orderId, String protocol, String referenceType) {
        return Optional.ofNullable(selectByOrder(orderId, protocol, referenceType));
    }

    default OrderExternalReference save(OrderExternalReference reference) {
        insert(reference);
        return reference;
    }
}
