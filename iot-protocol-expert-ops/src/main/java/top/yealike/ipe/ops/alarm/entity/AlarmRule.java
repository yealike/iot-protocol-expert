package top.yealike.ipe.ops.alarm.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 告警规则：指标 + 比较 + 阈值 + 持续时间。
 *
 * <p>作用域二选一：{@code deviceId} 有值表示只对该设备生效；否则按 {@code productId}
 * 对该产品下所有设备生效。不做通用表达式引擎——需要复杂逻辑时再引入场景规则， 而不是把规则表写成 SQL 字符串。
 */
@Entity
@Table(name = "alarm_rule")
public class AlarmRule {

    public static final String LEVEL_INFO = "INFO";
    public static final String LEVEL_WARN = "WARN";
    public static final String LEVEL_MAJOR = "MAJOR";
    public static final String LEVEL_CRITICAL = "CRITICAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "rule_code", nullable = false, unique = true, length = 64)
    public String ruleCode;

    @Column(nullable = false, length = 128)
    public String name;

    @Column(name = "product_id")
    public Long productId;

    @Column(name = "device_id")
    public Long deviceId;

    @Column(name = "channel_no", length = 16)
    public String channelNo;

    @Column(nullable = false, length = 16)
    public String level = LEVEL_WARN;

    @Column(name = "metric_property", nullable = false, length = 64)
    public String metricProperty;

    /** gt / ge / lt / le / eq / ne。 */
    @Column(nullable = false, length = 8)
    public String operator;

    @Column(precision = 20, scale = 6)
    public BigDecimal threshold;

    @Column(name = "duration_seconds", nullable = false)
    public int durationSeconds;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(length = 512)
    public String description;

    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;

    public boolean isDeviceScoped() {
        return deviceId != null;
    }
}
