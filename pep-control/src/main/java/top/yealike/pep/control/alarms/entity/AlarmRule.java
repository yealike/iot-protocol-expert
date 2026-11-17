package top.yealike.pep.control.alarms.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 告警规则：指标 + 比较 + 阈值 + 持续时间。
 *
 * <p>作用域二选一：{@code deviceId} 有值表示只对该设备生效；否则按 {@code productId}
 * 对该产品下所有设备生效。不做通用表达式引擎——需要复杂逻辑时再引入场景规则， 而不是把规则表写成 SQL 字符串。
 */
public class AlarmRule {

    public static final String LEVEL_INFO = "INFO";
    public static final String LEVEL_WARN = "WARN";
    public static final String LEVEL_MAJOR = "MAJOR";
    public static final String LEVEL_CRITICAL = "CRITICAL";

    public Long id;

    public String ruleCode;

    public String name;

    public Long productId;

    public Long deviceId;

    public String channelNo;

    public String level = LEVEL_WARN;

    public String metricProperty;

    /** gt / ge / lt / le / eq / ne。 */
    public String operator;

    public BigDecimal threshold;

    public int durationSeconds;

    public boolean enabled = true;

    public String description;

    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;

    public boolean isDeviceScoped() {
        return deviceId != null;
    }
}
