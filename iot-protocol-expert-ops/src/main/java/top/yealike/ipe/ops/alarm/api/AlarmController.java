package top.yealike.ipe.ops.alarm.api;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import top.yealike.ipe.ops.alarm.entity.AlarmRecord;
import top.yealike.ipe.ops.alarm.entity.AlarmRule;
import top.yealike.ipe.ops.alarm.service.AlarmService;
import top.yealike.ipe.ops.common.PageResult;
import top.yealike.ipe.ops.device.dto.DeviceDtos;
import top.yealike.ipe.ops.service.AuditService;

/** 告警：记录与规则。 */
@RestController
@RequestMapping("/api/ops/alarms")
public class AlarmController {

    private final AlarmService alarms;
    private final AuditService audits;

    public AlarmController(AlarmService alarms, AuditService audits) {
        this.alarms = alarms;
        this.audits = audits;
    }

    @GetMapping
    public PageResult<DeviceDtos.AlarmView> query(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) String deviceType,
            @RequestParam(required = false) Long stationId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return alarms.query(state, level, deviceId, deviceType, stationId, keyword, page, size);
    }

    public record HandleRequest(String note) {}

    @PostMapping("/{id}/ack")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public Map<String, Object> ack(
            @PathVariable Long id, @RequestBody(required = false) HandleRequest body, Principal principal) {
        AlarmRecord record = alarms.ack(id, name(principal), body == null ? null : body.note());
        audits.record(name(principal), "alarm.ack", "alarm", String.valueOf(id), "SUCCESS", record.title);
        return Map.of("id", record.id, "state", record.state);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public Map<String, Object> close(
            @PathVariable Long id, @RequestBody(required = false) HandleRequest body, Principal principal) {
        AlarmRecord record = alarms.close(id, name(principal), body == null ? null : body.note());
        audits.record(name(principal), "alarm.close", "alarm", String.valueOf(id), "SUCCESS", record.title);
        return Map.of("id", record.id, "state", record.state);
    }

    // ------------------------------------------------------------------ 规则

    @GetMapping("/rules")
    public List<AlarmRule> rules() {
        return alarms.listRules();
    }

    @PostMapping("/rules")
    @PreAuthorize("hasRole('ADMIN')")
    public AlarmRule saveRule(@RequestBody AlarmRule rule, Principal principal) {
        AlarmRule saved = alarms.saveRule(rule);
        audits.record(
                name(principal), "alarm.rule.save", "alarm-rule", String.valueOf(saved.id), "SUCCESS", saved.ruleCode);
        return saved;
    }

    @DeleteMapping("/rules/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> deleteRule(@PathVariable Long id, Principal principal) {
        alarms.deleteRule(id);
        audits.record(name(principal), "alarm.rule.delete", "alarm-rule", String.valueOf(id), "SUCCESS", null);
        return Map.of("deleted", true);
    }

    private static String name(Principal principal) {
        return principal == null ? "anonymous" : principal.getName();
    }
}
