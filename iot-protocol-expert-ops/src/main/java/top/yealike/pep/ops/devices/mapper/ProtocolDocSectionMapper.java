package top.yealike.pep.ops.devices.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.ops.devices.domain.ProtocolDocSection;

public interface ProtocolDocSectionMapper {

    List<ProtocolDocSection> selectByFrame(
            @Param("protocol") String protocol,
            @Param("version") String version,
            @Param("frameOrAction") String frameOrAction);

    List<ProtocolDocSection> search(
            @Param("protocol") String protocol, @Param("version") String version, @Param("keyword") String keyword);

    default List<ProtocolDocSection> findByFrame(String protocol, String version, String frameOrAction) {
        return selectByFrame(protocol, version, frameOrAction);
    }
}
