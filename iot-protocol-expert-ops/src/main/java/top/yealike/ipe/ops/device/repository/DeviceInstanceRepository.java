package top.yealike.ipe.ops.device.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import top.yealike.ipe.ops.device.entity.DeviceInstance;

public interface DeviceInstanceRepository
        extends JpaRepository<DeviceInstance, Long>, JpaSpecificationExecutor<DeviceInstance> {

    Optional<DeviceInstance> findByDeviceCode(String deviceCode);

    Optional<DeviceInstance> findByProtocolAndProtocolDeviceId(String protocol, String protocolDeviceId);

    List<DeviceInstance> findByStationId(Long stationId);

    List<DeviceInstance> findByProductId(Long productId);

    List<DeviceInstance> findByDeviceType(String deviceType);

    List<DeviceInstance> findByDeviceTypeIn(Collection<String> deviceTypes);

    List<DeviceInstance> findByParentId(Long parentId);

    long countByState(String state);

    long countByDeviceTypeAndState(String deviceType, String state);

    long countByDeviceType(String deviceType);

    long countByProductId(Long productId);

    @Modifying
    @Query("update DeviceInstance d set d.state = 'OFFLINE', d.sessionId = null, d.updatedAt = :now "
            + "where d.sessionId = 'gateway-telemetry' and d.state in ('ONLINE', 'FAULT') and d.lastSeen < :cutoff")
    int expireGatewayDevices(@Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now);
}
