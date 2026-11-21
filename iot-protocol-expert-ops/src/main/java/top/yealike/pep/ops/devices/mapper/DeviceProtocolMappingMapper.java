package top.yealike.pep.ops.devices.mapper;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.ops.devices.entity.DeviceProtocolMapping;

public interface DeviceProtocolMappingMapper {

    List<DeviceProtocolMapping> selectPage(@Param("afterId") Long afterId, @Param("limit") int limit);

    List<DeviceProtocolMapping> selectByDeviceIds(@Param("deviceIds") Collection<Long> deviceIds);

    DeviceProtocolMapping selectByDeviceId(@Param("deviceId") Long deviceId);

    DeviceProtocolMapping selectByProtocolAndThirdPartyDeviceId(
            @Param("protocol") String protocol, @Param("thirdPartyDeviceId") String thirdPartyDeviceId);

    int insert(DeviceProtocolMapping mapping);

    int update(DeviceProtocolMapping mapping);

    int deleteByDeviceId(@Param("deviceId") Long deviceId);

    default Optional<DeviceProtocolMapping> findByDeviceId(Long deviceId) {
        return Optional.ofNullable(selectByDeviceId(deviceId));
    }

    default Optional<DeviceProtocolMapping> findByProtocolAndThirdPartyDeviceId(
            String protocol, String thirdPartyDeviceId) {
        return Optional.ofNullable(selectByProtocolAndThirdPartyDeviceId(protocol, thirdPartyDeviceId));
    }

    default DeviceProtocolMapping save(DeviceProtocolMapping mapping) {
        if (mapping.id == null) {
            insert(mapping);
        } else {
            update(mapping);
        }
        return mapping;
    }
}
