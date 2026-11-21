package top.yealike.pep.ops.devices.mapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.ops.devices.entity.DeviceInstance;

public interface DeviceInstanceMapper {

    DeviceInstance selectById(@Param("id") Long id);

    DeviceInstance selectByDeviceCode(@Param("deviceCode") String deviceCode);

    DeviceInstance selectByPlatformGunNo(@Param("platformGunNo") String platformGunNo);

    List<DeviceInstance> selectAll();

    List<DeviceInstance> selectByIds(@Param("ids") Collection<Long> ids);

    List<DeviceInstance> selectByProductId(@Param("productId") Long productId);

    List<DeviceInstance> search(
            @Param("keyword") String keyword,
            @Param("deviceSn") String deviceSn,
            @Param("platformGunNo") String platformGunNo,
            @Param("states") List<String> states,
            @Param("productId") Long productId,
            @Param("enabled") Boolean enabled,
            @Param("orderBy") String orderBy,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countSearch(
            @Param("keyword") String keyword,
            @Param("deviceSn") String deviceSn,
            @Param("platformGunNo") String platformGunNo,
            @Param("states") List<String> states,
            @Param("productId") Long productId,
            @Param("enabled") Boolean enabled);

    long selectCountByProductId(@Param("productId") Long productId);

    String selectNextDeviceSnCandidate();

    String selectMappingSnapshotToken();

    int expireGatewayDevices(@Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now);

    int insert(DeviceInstance device);

    int update(DeviceInstance device);

    boolean hasDeletionBlockers(@Param("id") Long id);

    int deleteTags(@Param("id") Long id);

    int deleteLatestProperties(@Param("id") Long id);

    int deleteById(@Param("id") Long id);

    default Optional<DeviceInstance> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default Optional<DeviceInstance> findByDeviceCode(String deviceCode) {
        return Optional.ofNullable(selectByDeviceCode(deviceCode));
    }

    default Optional<DeviceInstance> findByPlatformGunNo(String platformGunNo) {
        return Optional.ofNullable(selectByPlatformGunNo(platformGunNo));
    }

    default List<DeviceInstance> findAll() {
        return selectAll();
    }

    default List<DeviceInstance> findAllById(Collection<Long> ids) {
        return ids == null || ids.isEmpty() ? List.of() : selectByIds(ids);
    }

    default List<DeviceInstance> findByProductId(Long productId) {
        return selectByProductId(productId);
    }

    default long countByProductId(Long productId) {
        return selectCountByProductId(productId);
    }

    default DeviceInstance save(DeviceInstance device) {
        if (device.id == null) {
            insert(device);
        } else {
            update(device);
        }
        return device;
    }

    default List<DeviceInstance> saveAll(List<DeviceInstance> devices) {
        devices.forEach(this::save);
        return devices;
    }

    default void delete(DeviceInstance device) {
        if (device != null && device.id != null) {
            deleteById(device.id);
        }
    }
}
