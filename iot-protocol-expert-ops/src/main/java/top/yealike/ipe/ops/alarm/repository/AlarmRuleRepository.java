package top.yealike.ipe.ops.alarm.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import top.yealike.ipe.ops.alarm.entity.AlarmRule;

public interface AlarmRuleRepository extends JpaRepository<AlarmRule, Long>, JpaSpecificationExecutor<AlarmRule> {

    Optional<AlarmRule> findByRuleCode(String ruleCode);

    List<AlarmRule> findByEnabledTrue();

    List<AlarmRule> findByProductIdAndEnabledTrue(Long productId);

    List<AlarmRule> findByDeviceIdAndEnabledTrue(Long deviceId);

    List<AlarmRule> findByProductId(Long productId);
}
