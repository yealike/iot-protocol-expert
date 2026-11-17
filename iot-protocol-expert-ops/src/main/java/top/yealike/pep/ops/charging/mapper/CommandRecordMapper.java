package top.yealike.pep.ops.charging.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.ops.charging.domain.CommandRecord;

public interface CommandRecordMapper {

    CommandRecord selectById(@Param("id") Long id);

    List<CommandRecord> selectTopByDevice(@Param("deviceId") Long deviceId, @Param("limit") int limit);

    List<CommandRecord> selectTop(@Param("limit") int limit);

    int insert(CommandRecord record);

    int update(CommandRecord record);

    default List<CommandRecord> findTop100ByDeviceIdOrderByIdDesc(Long deviceId) {
        return selectTopByDevice(deviceId, 100);
    }

    default List<CommandRecord> findTop100ByOrderByIdDesc() {
        return selectTop(100);
    }

    default CommandRecord save(CommandRecord record) {
        if (record.id == null) {
            insert(record);
        } else {
            update(record);
        }
        return record;
    }
}
