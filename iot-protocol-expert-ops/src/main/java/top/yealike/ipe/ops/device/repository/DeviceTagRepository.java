package top.yealike.ipe.ops.device.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import top.yealike.ipe.ops.device.entity.DeviceTag;

public interface DeviceTagRepository extends JpaRepository<DeviceTag, Long> {

    List<DeviceTag> findByDeviceIdOrderByTagKeyAsc(Long deviceId);

    Optional<DeviceTag> findByDeviceIdAndTagKey(Long deviceId, String tagKey);

    java.util.List<DeviceTag> findByDeviceIdInOrderByDeviceIdAsc(java.util.Collection<Long> deviceIds);

    void deleteByDeviceId(Long deviceId);
}
