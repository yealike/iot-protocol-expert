package top.yealike.pep.control.devices.mapper;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.control.devices.entity.DeviceTag;

public interface DeviceTagMapper {

    DeviceTag selectByDeviceIdAndTagKey(@Param("deviceId") Long deviceId, @Param("tagKey") String tagKey);

    List<DeviceTag> selectByDeviceIdOrderByTagKeyAsc(@Param("deviceId") Long deviceId);

    List<DeviceTag> selectByDeviceIds(@Param("deviceIds") Collection<Long> deviceIds);

    int insert(DeviceTag tag);

    int update(DeviceTag tag);

    int deleteById(@Param("id") Long id);

    int deleteByDeviceId(@Param("deviceId") Long deviceId);

    default Optional<DeviceTag> findByDeviceIdAndTagKey(Long deviceId, String tagKey) {
        return Optional.ofNullable(selectByDeviceIdAndTagKey(deviceId, tagKey));
    }

    default List<DeviceTag> findByDeviceIdOrderByTagKeyAsc(Long deviceId) {
        return selectByDeviceIdOrderByTagKeyAsc(deviceId);
    }

    default List<DeviceTag> findByDeviceIdInOrderByDeviceIdAsc(Collection<Long> deviceIds) {
        return deviceIds == null || deviceIds.isEmpty() ? List.of() : selectByDeviceIds(deviceIds);
    }

    default DeviceTag save(DeviceTag tag) {
        if (tag.id == null) {
            insert(tag);
        } else {
            update(tag);
        }
        return tag;
    }
}
