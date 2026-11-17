package top.yealike.pep.ops.devices.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.ops.devices.domain.FrameLog;

public interface FrameLogMapper {

    List<FrameLog> search(
            @Param("deviceId") Long deviceId,
            @Param("protocol") String protocol,
            @Param("protocolDeviceId") String protocolDeviceId,
            @Param("messageId") String messageId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("limit") int limit);

    List<FrameLog> selectRecent(@Param("limit") int limit);

    List<FrameLog> selectRecentByDevice(@Param("deviceId") Long deviceId, @Param("limit") int limit);

    long countByDirection(@Param("direction") String direction);

    int insert(FrameLog frame);

    default List<FrameLog> findTop200ByDeviceIdOrderByCreatedAtDesc(Long deviceId) {
        return selectRecentByDevice(deviceId, 200);
    }

    default FrameLog save(FrameLog frame) {
        insert(frame);
        return frame;
    }
}
