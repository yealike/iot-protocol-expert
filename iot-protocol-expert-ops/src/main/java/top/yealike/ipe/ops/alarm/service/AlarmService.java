package top.yealike.ipe.ops.alarm.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.ipe.ops.alarm.entity.AlarmRecord;
import top.yealike.ipe.ops.alarm.entity.AlarmRule;
import top.yealike.ipe.ops.alarm.repository.AlarmRecordRepository;
import top.yealike.ipe.ops.alarm.repository.AlarmRuleRepository;
import top.yealike.ipe.ops.common.PageResult;
import top.yealike.ipe.ops.common.PageSupport;
import top.yealike.ipe.ops.device.dto.DeviceDtos;
import top.yealike.ipe.ops.device.entity.DeviceInstance;
import top.yealike.ipe.ops.device.entity.DevicePropertyLatest;
import top.yealike.ipe.ops.device.repository.DeviceInstanceRepository;
import top.yealike.ipe.ops.thingmodel.ThingModelCodec;
import top.yealike.ipe.ops.thingmodel.ThingValues;

/**
 * 告警服务：规则命中 → 记录。
 *
 * <p>三个刻意的设计：
 *
 * <ol>
 *   <li>规则与记录分离。规则可挂在产品上批量生效，也可覆盖到单设备。
 *   <li>持续时间用「首次越限时间」判定，越限消失即清除；不做滑动窗口，够用且可解释。
 *   <li>条件恢复后自动关闭记录，避免 OPEN 记录无限堆积；人工确认只改状态，不改判定。
 * </ol>
 */
@Service
public class AlarmService {

    private static final Logger log = LoggerFactory.getLogger(AlarmService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int AUTO_CLOSE_AFTER_MINUTES = 5;

    private final AlarmRuleRepository rules;
    private final AlarmRecordRepository records;
    private final DeviceInstanceRepository devices;

    private final AtomicLong ruleEpoch = new AtomicLong();
    private final Map<Long, CachedRules> ruleCache = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public AlarmService(AlarmRuleRepository rules, AlarmRecordRepository records, DeviceInstanceRepository devices) {
        this.rules = rules;
        this.records = records;
        this.devices = devices;
    }

    private record CachedRules(long epoch, List<AlarmRule> items) {}

    /** 持续时间的待判定状态：越限首次发生时间 + 最近一次越限值。 */
    private record Pending(Long ruleId, Long deviceId, String channelNo, LocalDateTime firstAt, BigDecimal value) {}

    // ------------------------------------------------------------------ 规则缓存

    private List<AlarmRule> rulesFor(DeviceInstance device) {
        long epoch = ruleEpoch.get();
        CachedRules cached = ruleCache.get(device.id);
        if (cached != null && cached.epoch() == epoch) {
            return cached.items();
        }
        List<AlarmRule> merged = new ArrayList<>(rules.findByProductIdAndEnabledTrue(device.productId));
        for (AlarmRule rule : rules.findByDeviceIdAndEnabledTrue(device.id)) {
            merged.removeIf(existing -> existing.id.equals(rule.id));
            merged.add(rule);
        }
        ruleCache.put(device.id, new CachedRules(epoch, merged));
        return merged;
    }

    /** 规则变更后调用，让所有设备重新加载。 */
    public void invalidateRules() {
        ruleEpoch.incrementAndGet();
        ruleCache.clear();
    }

    // ------------------------------------------------------------------ 判定

    /** 对一次属性写入做规则判定。由 {@code DevicePropertyService} 在写入数值属性后调用。 */
    @Transactional
    public void evaluate(
            DeviceInstance device, String channelNo, String propertyId, BigDecimal value, LocalDateTime at) {
        if (value == null) {
            return;
        }
        String effectiveChannel = channelNo == null ? "" : channelNo;
        for (AlarmRule rule : rulesFor(device)) {
            if (!propertyId.equals(rule.metricProperty)) {
                continue;
            }
            if (rule.channelNo != null && !rule.channelNo.isBlank() && !rule.channelNo.equals(channelNo)) {
                continue;
            }
            boolean hit = ThingValues.compare(rule.operator, value, rule.threshold);
            String key = rule.id + "|" + device.id + "|" + effectiveChannel;
            if (!hit) {
                pending.remove(key);
                autoClosePending(rule, device, effectiveChannel, at);
                continue;
            }
            Pending state = pending.compute(
                    key,
                    (k, existing) -> existing == null
                            ? new Pending(rule.id, device.id, effectiveChannel, at, value)
                            : new Pending(
                                    existing.ruleId(),
                                    existing.deviceId(),
                                    existing.channelNo(),
                                    existing.firstAt(),
                                    value));
            if (rule.durationSeconds > 0
                    && state.firstAt().plusSeconds(rule.durationSeconds).isAfter(at)) {
                continue;
            }
            raise(rule, device, effectiveChannel, value, state.firstAt(), at);
        }
    }

    /**
     * 持续时间到点的巡检。
     *
     * <p>越限值只在设备上报时到达，若不主动巡检，"持续 30 秒"的规则要等到下一次上报 才会触发——对低频设备等于永远不触发。这里按 5 秒节拍把到期且仍成立的条件补上。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5_000)
    public void sweepPending() {
        if (pending.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, Pending> entry : pending.entrySet()) {
            Pending state = entry.getValue();
            AlarmRule rule = rules.findById(state.ruleId()).orElse(null);
            if (rule == null || !rule.enabled) {
                pending.remove(entry.getKey());
                continue;
            }
            if (state.firstAt().plusSeconds(Math.max(rule.durationSeconds, 0)).isAfter(now)) {
                continue;
            }
            DeviceInstance device = devices.findById(state.deviceId()).orElse(null);
            if (device == null) {
                pending.remove(entry.getKey());
                continue;
            }
            try {
                raise(rule, device, state.channelNo(), state.value(), state.firstAt(), now);
            } catch (RuntimeException e) {
                log.warn("告警巡检失败 rule={} device={}: {}", rule.ruleCode, device.deviceCode, e.getMessage());
            }
        }
    }

    private void raise(
            AlarmRule rule,
            DeviceInstance device,
            String channelNo,
            BigDecimal value,
            LocalDateTime firstAt,
            LocalDateTime at) {
        AlarmRecord record = records.findTopByRuleIdAndDeviceIdAndChannelNoAndStateOrderByLastAtDesc(
                        rule.id, device.id, channelNo, AlarmRecord.STATE_OPEN)
                .orElse(null);
        if (record == null) {
            record = new AlarmRecord();
            record.ruleId = rule.id;
            record.deviceId = device.id;
            // 统一用空串而不是 NULL：唯一键与查询都按空串匹配，避免 NULL 相等的陷阱
            record.channelNo = channelNo;
            record.level = rule.level;
            record.title = rule.name + "（" + device.name + "）";
            record.state = AlarmRecord.STATE_OPEN;
            record.firstAt = firstAt;
            record.lastAt = at;
            record.createdAt = LocalDateTime.now();
            record.detail = detail(rule, value, at);
            records.save(record);
            log.info("告警触发 rule={} device={} value={}", rule.ruleCode, device.deviceCode, value);
        } else {
            record.lastAt = at;
            record.detail = detail(rule, value, at);
            records.save(record);
        }
    }

    private String detail(AlarmRule rule, BigDecimal value, LocalDateTime at) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ruleCode", rule.ruleCode);
        detail.put("metric", rule.metricProperty);
        detail.put("operator", rule.operator);
        detail.put("threshold", rule.threshold);
        detail.put("actual", value);
        detail.put("evaluatedAt", at.format(TIME));
        return ThingModelCodec.mapper().writeValueAsString(detail);
    }

    private void autoClosePending(AlarmRule rule, DeviceInstance device, String channelNo, LocalDateTime at) {
        records.findTopByRuleIdAndDeviceIdAndChannelNoAndStateOrderByLastAtDesc(
                        rule.id, device.id, channelNo, AlarmRecord.STATE_OPEN)
                .filter(record ->
                        record.lastAt.plusMinutes(AUTO_CLOSE_AFTER_MINUTES).isBefore(at))
                .ifPresent(record -> {
                    record.state = AlarmRecord.STATE_CLOSED;
                    record.handledAt = at;
                    record.handledBy = "system";
                    record.handlingNote = "条件恢复，自动关闭";
                    records.save(record);
                });
    }

    // ------------------------------------------------------------------ 人工处理

    @Transactional
    public AlarmRecord ack(Long id, String operator, String note) {
        AlarmRecord record = require(id);
        if (AlarmRecord.STATE_CLOSED.equals(record.state)) {
            throw new IllegalArgumentException("告警已关闭，无法确认");
        }
        record.state = AlarmRecord.STATE_ACK;
        record.handledBy = operator;
        record.handledAt = LocalDateTime.now();
        record.handlingNote = note;
        return records.save(record);
    }

    @Transactional
    public AlarmRecord close(Long id, String operator, String note) {
        AlarmRecord record = require(id);
        record.state = AlarmRecord.STATE_CLOSED;
        record.handledBy = operator;
        record.handledAt = LocalDateTime.now();
        record.handlingNote = note;
        return records.save(record);
    }

    private AlarmRecord require(Long id) {
        return records.findById(id).orElseThrow(() -> new IllegalArgumentException("告警记录不存在: " + id));
    }

    // ------------------------------------------------------------------ 查询

    public PageResult<DeviceDtos.AlarmView> query(
            String state,
            String level,
            Long deviceId,
            String deviceType,
            Long stationId,
            String keyword,
            Integer page,
            Integer size) {
        Specification<AlarmRecord> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (state != null && !state.isBlank()) {
                predicates.add(cb.equal(root.get("state"), state.trim().toUpperCase()));
            }
            if (level != null && !level.isBlank()) {
                predicates.add(cb.equal(root.get("level"), level.trim().toUpperCase()));
            }
            if (deviceId != null) {
                predicates.add(cb.equal(root.get("deviceId"), deviceId));
            }
            if (keyword != null && !keyword.isBlank()) {
                predicates.add(cb.like(
                        cb.lower(root.get("title")), "%" + keyword.trim().toLowerCase() + "%"));
            }
            if (deviceType != null && !deviceType.isBlank() || stationId != null) {
                var sub = query.subquery(Long.class);
                var deviceRoot = sub.from(DeviceInstance.class);
                List<jakarta.persistence.criteria.Predicate> inner = new ArrayList<>();
                if (deviceType != null && !deviceType.isBlank()) {
                    inner.add(cb.equal(
                            deviceRoot.get("deviceType"), deviceType.trim().toUpperCase()));
                }
                if (stationId != null) {
                    inner.add(cb.equal(deviceRoot.get("stationId"), stationId));
                }
                sub.select(deviceRoot.get("id")).where(inner.toArray(jakarta.persistence.criteria.Predicate[]::new));
                predicates.add(root.get("deviceId").in(sub));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        Page<AlarmRecord> result = records.findAll(
                spec, PageSupport.pageable(page, size, "-lastAt", Set.of("lastAt", "firstAt", "id", "level")));
        Map<Long, DeviceInstance> deviceMap = deviceMap(result.getContent());
        return PageResult.of(result, record -> view(record, deviceMap));
    }

    public List<DeviceDtos.AlarmView> openFor(Long deviceId) {
        List<AlarmRecord> list = records.findByDeviceIdAndStateOrderByLastAtDesc(deviceId, AlarmRecord.STATE_OPEN);
        Map<Long, DeviceInstance> deviceMap = deviceMap(list);
        return list.stream().map(record -> view(record, deviceMap)).toList();
    }

    public List<DeviceDtos.AlarmView> latestOpen(int limit) {
        List<AlarmRecord> list = records.findTop50ByStateOrderByLastAtDesc(AlarmRecord.STATE_OPEN);
        if (list.size() > limit) {
            list = list.subList(0, limit);
        }
        Map<Long, DeviceInstance> deviceMap = deviceMap(list);
        return list.stream().map(record -> view(record, deviceMap)).toList();
    }

    public long countOpen() {
        return records.countByState(AlarmRecord.STATE_OPEN);
    }

    public long countOpenByLevel(String level) {
        return records.countByStateAndLevel(AlarmRecord.STATE_OPEN, level);
    }

    /** 设备类型维度的未关闭告警数。 */
    public Map<String, Long> countOpenByDeviceType() {
        Map<String, Long> counter = new LinkedHashMap<>();
        for (AlarmRecord record : records.findTop50ByStateOrderByLastAtDesc(AlarmRecord.STATE_OPEN)) {
            devices.findById(record.deviceId).ifPresent(device -> counter.merge(device.deviceType, 1L, Long::sum));
        }
        return counter;
    }

    private Map<Long, DeviceInstance> deviceMap(List<AlarmRecord> list) {
        List<Long> ids = list.stream().map(record -> record.deviceId).distinct().toList();
        Map<Long, DeviceInstance> map = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        for (DeviceInstance device : devices.findAllById(ids)) {
            map.put(device.id, device);
        }
        return map;
    }

    private DeviceDtos.AlarmView view(AlarmRecord record, Map<Long, DeviceInstance> deviceMap) {
        DeviceInstance device = deviceMap.get(record.deviceId);
        return new DeviceDtos.AlarmView(
                record.id,
                record.deviceId,
                device == null ? null : device.deviceCode,
                device == null ? null : device.name,
                record.channelNo,
                record.level,
                record.title,
                record.state,
                record.firstAt == null ? null : record.firstAt.format(TIME),
                record.lastAt == null ? null : record.lastAt.format(TIME),
                record.handledBy,
                record.handledAt == null ? null : record.handledAt.format(TIME),
                record.handlingNote);
    }

    // ------------------------------------------------------------------ 规则维护

    public List<AlarmRule> listRules() {
        return rules.findAll();
    }

    @Transactional
    public AlarmRule saveRule(AlarmRule input) {
        if (input.ruleCode == null || input.ruleCode.isBlank()) {
            throw new IllegalArgumentException("规则编码不能为空");
        }
        if (input.metricProperty == null || input.metricProperty.isBlank()) {
            throw new IllegalArgumentException("告警指标不能为空");
        }
        if (!Set.of("gt", "ge", "lt", "le", "eq", "ne").contains(input.operator)) {
            throw new IllegalArgumentException("不支持的比较符: " + input.operator);
        }
        AlarmRule rule = rules.findByRuleCode(input.ruleCode.trim()).orElseGet(AlarmRule::new);
        rule.ruleCode = input.ruleCode.trim();
        rule.name = input.name;
        rule.productId = input.productId;
        rule.deviceId = input.deviceId;
        rule.channelNo = input.channelNo;
        rule.level = input.level == null ? AlarmRule.LEVEL_WARN : input.level;
        rule.metricProperty = input.metricProperty;
        rule.operator = input.operator;
        rule.threshold = input.threshold;
        rule.durationSeconds = Math.max(input.durationSeconds, 0);
        rule.enabled = input.enabled;
        rule.description = input.description;
        if (rule.id == null) {
            rule.createdAt = LocalDateTime.now();
        }
        rule.updatedAt = LocalDateTime.now();
        AlarmRule saved = rules.save(rule);
        invalidateRules();
        return saved;
    }

    @Transactional
    public void deleteRule(Long id) {
        rules.deleteById(id);
        invalidateRules();
    }

    /** 供属性写入侧复用：把最新值包成一次判定需要的形态。 */
    public void evaluateValue(DeviceInstance device, DevicePropertyLatest row) {
        if (row != null && row.valueNum != null) {
            evaluate(device, row.channelNo, row.propertyId, row.valueNum, LocalDateTime.now());
        }
    }
}
