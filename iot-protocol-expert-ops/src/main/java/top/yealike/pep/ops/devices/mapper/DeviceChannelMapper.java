package top.yealike.pep.ops.devices.mapper;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.ops.devices.entity.DeviceChannel;

public interface DeviceChannelMapper {

    DeviceChannel selectByDeviceIdAndChannelNo(@Param("deviceId") Long deviceId, @Param("channelNo") String channelNo);

    List<DeviceChannel> selectByDeviceIdOrderByChannelNoAsc(@Param("deviceId") Long deviceId);

    List<DeviceChannel> selectByDeviceIds(@Param("deviceIds") Collection<Long> deviceIds);

    long countByDeviceId(@Param("deviceId") Long deviceId);

    long countByDeviceIdAndChannelType(@Param("deviceId") Long deviceId, @Param("channelType") String channelType);

    int insert(DeviceChannel channel);

    int update(DeviceChannel channel);

    int deleteById(@Param("id") Long id);

    int deleteByDeviceId(@Param("deviceId") Long deviceId);

    default Optional<DeviceChannel> findByDeviceIdAndChannelNo(Long deviceId, String channelNo) {
        return Optional.ofNullable(selectByDeviceIdAndChannelNo(deviceId, channelNo));
    }

    default List<DeviceChannel> findByDeviceIdOrderByChannelNoAsc(Long deviceId) {
        return selectByDeviceIdOrderByChannelNoAsc(deviceId);
    }

    default List<DeviceChannel> findByDeviceIdIn(Collection<Long> deviceIds) {
        return deviceIds == null || deviceIds.isEmpty() ? List.of() : selectByDeviceIds(deviceIds);
    }

    default DeviceChannel save(DeviceChannel channel) {
        if (channel.id == null) {
            insert(channel);
        } else {
            update(channel);
        }
        return channel;
    }
}
