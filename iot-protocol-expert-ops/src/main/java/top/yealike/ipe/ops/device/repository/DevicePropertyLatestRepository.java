package top.yealike.ipe.ops.device.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import top.yealike.ipe.ops.device.entity.DevicePropertyLatest;

public interface DevicePropertyLatestRepository extends JpaRepository<DevicePropertyLatest, Long> {

    List<DevicePropertyLatest> findByDeviceId(Long deviceId);

    List<DevicePropertyLatest> findByDeviceIdAndChannelNoOrderByPropertyIdAsc(Long deviceId, String channelNo);

    Optional<DevicePropertyLatest> findByDeviceIdAndChannelNoAndPropertyId(
            Long deviceId, String channelNo, String propertyId);

    List<DevicePropertyLatest> findByDeviceIdIn(List<Long> deviceIds);

    long countByPropertyIdAndValueTextAndDeviceIdIn(
            String propertyId, String valueText, java.util.Collection<Long> deviceIds);

    void deleteByDeviceId(Long deviceId);
}
