package top.yealike.pep.control.devices.mapper;

import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.control.devices.domain.ProtocolDoc;

public interface ProtocolDocMapper {

    ProtocolDoc selectByProtocolAndVersion(@Param("protocol") String protocol, @Param("version") String version);

    default Optional<ProtocolDoc> findByProtocolAndVersion(String protocol, String version) {
        return Optional.ofNullable(selectByProtocolAndVersion(protocol, version));
    }
}
