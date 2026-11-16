package top.yealike.ipe.ops.alarm.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "告警管理", description = "查询和处置设备告警，维护告警规则")
public class AlarmController {

    private final AlarmService alarms;
    private final AuditService audits;

    public AlarmController(AlarmService alarms, AuditService audits) {
        this.alarms = alarms;
        this.audits = audits;
    }

    @GetMapping
    @Operation(summary = "筛选告警记录")
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

    public record HandleRequest(
            @Schema(description = "处理备注") String note) {}

    @PostMapping("/{id}/ack")
    @Operation(summary = "确认告警")
    @PreAuthorize("hasAuthority('ops:operate')")
    public Map<String, Object> ack(
            @Parameter(description = "告警记录 ID") @PathVariable Long id,
            @RequestBody(required = false) HandleRequest body,
            Principal principal) {
        AlarmRecord record = alarms.ack(id, name(principal), body == null ? null : body.note());
        audits.record(name(principal), "alarm.ack", "alarm", String.valueOf(id), "SUCCESS", record.title);
        return Map.of("id", record.id, "state", record.state);
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "关闭告警")
    @PreAuthorize("hasAuthority('ops:operate')")
    public Map<String, Object> close(
            @Parameter(description = "告警记录 ID") @PathVariable Long id,
            @RequestBody(required = false) HandleRequest body,
            Principal principal) {
        AlarmRecord record = alarms.close(id, name(principal), body == null ? null : body.note());
        audits.record(name(principal), "alarm.close", "alarm", String.valueOf(id), "SUCCESS", record.title);
        return Map.of("id", record.id, "state", record.state);
    }

    // ------------------------------------------------------------------ 规则

    @GetMapping("/rules")
    @Operation(summary = "查询告警规则")
    public List<AlarmRule> rules() {
        return alarms.listRules();
    }

    @PostMapping("/rules")
    @Operation(summary = "创建或更新告警规则")
    @PreAuthorize("hasAuthority('ops:manage')")
    public AlarmRule saveRule(@RequestBody AlarmRule rule, Principal principal) {
        AlarmRule saved = alarms.saveRule(rule);
        audits.record(
                name(principal), "alarm.rule.save", "alarm-rule", String.valueOf(saved.id), "SUCCESS", saved.ruleCode);
        return saved;
    }

    @DeleteMapping("/rules/{id}")
    @Operation(summary = "删除告警规则")
    @PreAuthorize("hasAuthority('ops:manage')")
    public Map<String, Object> deleteRule(@PathVariable Long id, Principal principal) {
        alarms.deleteRule(id);
        audits.record(name(principal), "alarm.rule.delete", "alarm-rule", String.valueOf(id), "SUCCESS", null);
        return Map.of("deleted", true);
    }

    private static String name(Principal principal) {
        return principal == null ? "anonymous" : principal.getName();
    }
}
