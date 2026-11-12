package top.yealike.ipe.ops.device.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
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
}
