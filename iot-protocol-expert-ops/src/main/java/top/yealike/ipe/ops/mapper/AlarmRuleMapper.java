package top.yealike.ipe.ops.mapper;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import top.yealike.ipe.ops.alarm.entity.AlarmRule;

public interface AlarmRuleMapper {

    AlarmRule selectById(@Param("id") Long id);

    AlarmRule selectByRuleCode(@Param("ruleCode") String ruleCode);

    List<AlarmRule> selectEnabledByProductId(@Param("productId") Long productId);

    List<AlarmRule> selectEnabledByDeviceId(@Param("deviceId") Long deviceId);

    List<AlarmRule> selectByProductId(@Param("productId") Long productId);

    List<AlarmRule> selectAll();

    int insert(AlarmRule rule);

    int update(AlarmRule rule);

    int deleteById(@Param("id") Long id);

    default Optional<AlarmRule> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default Optional<AlarmRule> findByRuleCode(String ruleCode) {
        return Optional.ofNullable(selectByRuleCode(ruleCode));
    }

    default List<AlarmRule> findByProductIdAndEnabledTrue(Long productId) {
        return selectEnabledByProductId(productId);
    }

    default List<AlarmRule> findByDeviceIdAndEnabledTrue(Long deviceId) {
        return selectEnabledByDeviceId(deviceId);
    }

    default List<AlarmRule> findByProductId(Long productId) {
        return selectByProductId(productId);
    }

    default List<AlarmRule> findAll() {
        return selectAll();
    }

    default AlarmRule save(AlarmRule rule) {
        if (rule.id == null) {
            insert(rule);
        } else {
            update(rule);
        }
        return rule;
    }
}
