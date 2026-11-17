package top.yealike.pep.control.devices.mapper;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.control.devices.entity.DeviceEventLog;

public interface DeviceEventLogMapper {

    List<DeviceEventLog> selectTopByDevice(@Param("deviceId") Long deviceId, @Param("limit") int limit);

    List<DeviceEventLog> selectTopByDevices(@Param("deviceIds") Collection<Long> deviceIds, @Param("limit") int limit);

    int insert(DeviceEventLog event);

    default List<DeviceEventLog> findTop50ByDeviceIdOrderByOccurredAtDesc(Long deviceId) {
        return selectTopByDevice(deviceId, 50);
    }

    default List<DeviceEventLog> findTop20ByDeviceIdInOrderByOccurredAtDesc(List<Long> deviceIds) {
        return deviceIds == null || deviceIds.isEmpty() ? List.of() : selectTopByDevices(deviceIds, 20);
    }

    default DeviceEventLog save(DeviceEventLog event) {
        insert(event);
        return event;
    }
}
