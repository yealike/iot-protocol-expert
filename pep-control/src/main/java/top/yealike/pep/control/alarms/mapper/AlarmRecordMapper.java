package top.yealike.pep.control.alarms.mapper;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.control.alarms.entity.AlarmRecord;

public interface AlarmRecordMapper {

    AlarmRecord selectById(@Param("id") Long id);

    AlarmRecord selectTopOpen(
            @Param("ruleId") Long ruleId,
            @Param("deviceId") Long deviceId,
            @Param("channelNo") String channelNo,
            @Param("state") String state);

    List<AlarmRecord> selectByDeviceIdAndState(@Param("deviceId") Long deviceId, @Param("state") String state);

    List<AlarmRecord> selectTopByState(@Param("state") String state, @Param("limit") int limit);

    List<AlarmRecord> search(
            @Param("state") String state,
            @Param("level") String level,
            @Param("deviceId") Long deviceId,
            @Param("deviceType") String deviceType,
            @Param("stationId") Long stationId,
            @Param("keyword") String keyword,
            @Param("orderBy") String orderBy,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countSearch(
            @Param("state") String state,
            @Param("level") String level,
            @Param("deviceId") Long deviceId,
            @Param("deviceType") String deviceType,
            @Param("stationId") Long stationId,
            @Param("keyword") String keyword);

    long countByState(@Param("state") String state);

    long countByStateAndLevel(@Param("state") String state, @Param("level") String level);

    int insert(AlarmRecord record);

    int update(AlarmRecord record);

    default Optional<AlarmRecord> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default Optional<AlarmRecord> findTopByRuleIdAndDeviceIdAndChannelNoAndStateOrderByLastAtDesc(
            Long ruleId, Long deviceId, String channelNo, String state) {
        return Optional.ofNullable(selectTopOpen(ruleId, deviceId, channelNo, state));
    }

    default List<AlarmRecord> findByDeviceIdAndStateOrderByLastAtDesc(Long deviceId, String state) {
        return selectByDeviceIdAndState(deviceId, state);
    }

    default List<AlarmRecord> findTop50ByStateOrderByLastAtDesc(String state) {
        return selectTopByState(state, 50);
    }

    default AlarmRecord save(AlarmRecord record) {
        if (record.id == null) {
            insert(record);
        } else {
            update(record);
        }
        return record;
    }
}
