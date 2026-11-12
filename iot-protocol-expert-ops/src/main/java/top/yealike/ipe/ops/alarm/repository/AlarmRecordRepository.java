package top.yealike.ipe.ops.alarm.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import top.yealike.ipe.ops.alarm.entity.AlarmRecord;

public interface AlarmRecordRepository extends JpaRepository<AlarmRecord, Long>, JpaSpecificationExecutor<AlarmRecord> {

    Optional<AlarmRecord> findTopByRuleIdAndDeviceIdAndChannelNoAndStateOrderByLastAtDesc(
            Long ruleId, Long deviceId, String channelNo, String state);

    List<AlarmRecord> findByDeviceIdAndStateOrderByLastAtDesc(Long deviceId, String state);

    List<AlarmRecord> findTop50ByStateOrderByLastAtDesc(String state);

    long countByState(String state);

    long countByStateAndLevel(String state, String level);
}
