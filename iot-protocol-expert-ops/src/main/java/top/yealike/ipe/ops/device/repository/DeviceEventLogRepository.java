package top.yealike.ipe.ops.device.repository;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import top.yealike.ipe.ops.device.entity.DeviceEventLog;

public interface DeviceEventLogRepository extends JpaRepository<DeviceEventLog, Long> {

    Page<DeviceEventLog> findByDeviceIdOrderByOccurredAtDesc(Long deviceId, Pageable pageable);

    List<DeviceEventLog> findTop50ByDeviceIdOrderByOccurredAtDesc(Long deviceId);

    List<DeviceEventLog> findTop20ByDeviceIdInOrderByOccurredAtDesc(List<Long> deviceIds);
}
