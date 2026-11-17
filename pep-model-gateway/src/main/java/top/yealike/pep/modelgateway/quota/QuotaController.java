package top.yealike.pep.modelgateway.quota;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 额度查询与管理。
 *
 * <p>均要求控制服务的内部服务令牌；用户范围由控制服务解析后通过 scopeKind/subjectId 传入，
 * 网关不接受浏览器直接提交扣费主体。
 */
@RestController
@RequestMapping(path = "/api/model-gateway/v1", produces = "application/json")
public class QuotaController {

    private final QuotaService quota;

    public QuotaController(QuotaService quota) {
        this.quota = quota;
    }

    @Operation(summary = "查询主体额度", description = "返回每条策略的限额、已用、冻结、窗口起止与待对账数量")
    @GetMapping("/me/quota")
    public Map<String, Object> quota(
            @RequestParam(defaultValue = "USER") String scopeKind, @RequestParam String subjectId) {
        return quota.snapshot(scopeKind, subjectId);
    }

    @Operation(summary = "查询主体用量明细")
    @GetMapping("/me/usage")
    public Map<String, Object> usage(
            @RequestParam(defaultValue = "USER") String scopeKind,
            @RequestParam String subjectId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String projectId) {
        return Map.of("items", quota.usage(scopeKind, subjectId, limit, model, projectId));
    }

    @Operation(summary = "分配或调整额度", description = "必须携带 Idempotency-Key，写入变更审计")
    @PostMapping("/admin/quota-grants")
    public Map<String, Object> grant(
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody QuotaService.GrantRequest request) {
        return quota.grant(new QuotaService.GrantRequest(
                request.scopeKind(),
                request.subjectId(),
                request.mode(),
                request.limitUnits(),
                request.durationSeconds(),
                request.policyKey(),
                request.reason(),
                request.actorId(),
                idempotencyKey));
    }

    @Operation(summary = "停用或恢复额度账户")
    @PostMapping("/admin/quota-accounts/{accountId}/state")
    public Map<String, Object> state(
            @PathVariable UUID accountId,
            @RequestParam String state,
            @RequestParam(defaultValue = "system") String actorId,
            @RequestParam(defaultValue = "") String reason) {
        return quota.suspend(accountId, state, actorId, reason);
    }

    @Operation(summary = "追加用量调整", description = "结算后的更正只追加调整记录，不修改原始账本")
    @PostMapping("/admin/usage-adjustments")
    public Map<String, Object> adjust(
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody AdjustmentRequest request) {
        return quota.adjust(
                request.modelCallId(), request.deltaUnits(), request.reason(), request.operatorId(), idempotencyKey);
    }

    @Operation(summary = "对不确定调用做释放对账")
    @PostMapping("/admin/calls/{reservationId}/reconcile-release")
    public Map<String, Object> reconcile(@PathVariable UUID reservationId) {
        quota.reconcileRelease(reservationId);
        return Map.of("reservationId", reservationId.toString(), "state", "RELEASED");
    }

    public record AdjustmentRequest(UUID modelCallId, long deltaUnits, String reason, String operatorId) {}
}
