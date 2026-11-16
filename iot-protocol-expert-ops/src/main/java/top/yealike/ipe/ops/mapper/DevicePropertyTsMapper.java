package top.yealike.ipe.ops.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import top.yealike.ipe.ops.device.entity.DevicePropertyTs;

public interface DevicePropertyTsMapper {

    List<DevicePropertyTs> selectSeries(
            @Param("deviceId") Long deviceId,
            @Param("propertyId") String propertyId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    int insert(DevicePropertyTs point);

    int deleteOlderThan(@Param("before") LocalDateTime before);

    int deleteByDevice(@Param("deviceId") Long deviceId);

    default List<DevicePropertyTs> findByDeviceIdAndPropertyIdAndCollectedAtBetweenOrderByCollectedAtAsc(
            Long deviceId, String propertyId, LocalDateTime from, LocalDateTime to) {
        return selectSeries(deviceId, propertyId, from, to);
    }

    default DevicePropertyTs save(DevicePropertyTs point) {
        insert(point);
        return point;
    }
}
