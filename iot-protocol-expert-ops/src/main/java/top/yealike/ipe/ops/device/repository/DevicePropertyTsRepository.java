package top.yealike.ipe.ops.device.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import top.yealike.ipe.ops.device.entity.DevicePropertyTs;

public interface DevicePropertyTsRepository extends JpaRepository<DevicePropertyTs, Long> {

    List<DevicePropertyTs> findByDeviceIdAndPropertyIdAndCollectedAtBetweenOrderByCollectedAtAsc(
            Long deviceId, String propertyId, LocalDateTime from, LocalDateTime to);

    @Modifying
    @Query("delete from DevicePropertyTs t where t.collectedAt < :before")
    int deleteOlderThan(LocalDateTime before);

    @Modifying
    @Query("delete from DevicePropertyTs t where t.deviceId = :deviceId")
    int deleteByDevice(Long deviceId);
}
