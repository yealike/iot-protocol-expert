package top.yealike.ipe.ops.device.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import top.yealike.ipe.ops.device.entity.DeviceChannel;

public interface DeviceChannelRepository extends JpaRepository<DeviceChannel, Long> {

    List<DeviceChannel> findByDeviceIdOrderByChannelNoAsc(Long deviceId);

    Optional<DeviceChannel> findByDeviceIdAndChannelNo(Long deviceId, String channelNo);

    long countByDeviceId(Long deviceId);

    long countByDeviceIdAndChannelType(Long deviceId, String channelType);

    java.util.List<DeviceChannel> findByDeviceIdIn(java.util.Collection<Long> deviceIds);

    void deleteByDeviceId(Long deviceId);
}
