package top.yealike.pep.ops.devices.mapper;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.ops.devices.entity.DevicePropertyLatest;

public interface DevicePropertyLatestMapper {

    DevicePropertyLatest selectByDeviceAndProperty(
            @Param("deviceId") Long deviceId,
            @Param("channelNo") String channelNo,
            @Param("propertyId") String propertyId);

    List<DevicePropertyLatest> selectByDeviceId(@Param("deviceId") Long deviceId);

    List<DevicePropertyLatest> selectByDeviceIds(@Param("deviceIds") Collection<Long> deviceIds);

    long selectCountByPropertyIdAndValueTextAndDeviceIdIn(
            @Param("propertyId") String propertyId,
            @Param("valueText") String valueText,
            @Param("deviceIds") Collection<Long> deviceIds);

    int insert(DevicePropertyLatest row);

    int update(DevicePropertyLatest row);

    int deleteById(@Param("id") Long id);

    int deleteByDeviceId(@Param("deviceId") Long deviceId);

    default Optional<DevicePropertyLatest> findByDeviceIdAndChannelNoAndPropertyId(
            Long deviceId, String channelNo, String propertyId) {
        return Optional.ofNullable(selectByDeviceAndProperty(deviceId, channelNo, propertyId));
    }

    default List<DevicePropertyLatest> findByDeviceId(Long deviceId) {
        return selectByDeviceId(deviceId);
    }

    default List<DevicePropertyLatest> findByDeviceIdIn(List<Long> deviceIds) {
        return deviceIds == null || deviceIds.isEmpty() ? List.of() : selectByDeviceIds(deviceIds);
    }

    default long countByPropertyIdAndValueTextAndDeviceIdIn(
            String propertyId, String valueText, Collection<Long> deviceIds) {
        return deviceIds == null || deviceIds.isEmpty()
                ? 0
                : selectCountByPropertyIdAndValueTextAndDeviceIdIn(propertyId, valueText, deviceIds);
    }

    default DevicePropertyLatest save(DevicePropertyLatest row) {
        if (row.id == null) {
            insert(row);
        } else {
            update(row);
        }
        return row;
    }
}
