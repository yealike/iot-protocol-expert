package top.yealike.pep.ops.charging.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.ops.charging.domain.ChargeOrderSegment;

public interface ChargeOrderSegmentMapper {

    List<ChargeOrderSegment> selectByOrderId(@Param("orderId") Long orderId);

    int deleteByOrderId(@Param("orderId") Long orderId);

    int insertBatch(@Param("segments") List<ChargeOrderSegment> segments);

    default List<ChargeOrderSegment> findByOrderId(Long orderId) {
        return selectByOrderId(orderId);
    }

    default void replace(Long orderId, List<ChargeOrderSegment> segments) {
        deleteByOrderId(orderId);
        if (segments != null && !segments.isEmpty()) {
            insertBatch(segments);
        }
    }
}
