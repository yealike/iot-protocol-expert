package top.yealike.pep.modelgateway.api.quota;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.model.contract.gateway.QuotaAccountStateResult;
import top.yealike.pep.model.contract.gateway.QuotaGrantRequest;
import top.yealike.pep.model.contract.gateway.QuotaGrantResult;
import top.yealike.pep.model.contract.gateway.QuotaSnapshot;
import top.yealike.pep.model.contract.gateway.ReconcileReleaseResult;
import top.yealike.pep.model.contract.gateway.UsageAdjustmentResult;
import top.yealike.pep.model.contract.gateway.UsageList;
import top.yealike.pep.modelgateway.quota.QuotaService;
import top.yealike.pep.modelgateway.quota.dto.AdjustmentRequest;
import top.yealike.pep.modelgateway.quota.dto.GrantRequest;

/**
 * 额度查询与管理。
 *
 * <p>均要求控制服务的内部服务令牌；用户范围由控制服务解析后通过 scopeKind/subjectId 传入，
 * 网关不接受浏览器直接提交扣费主体。
 */
@RestController
@RequestMapping(path = "/api/model-gateway/v1", produces = "application/json")
@Tag(name = "模型网关-额度管理", description = "模型额度查询、授予、停用、用量调整与释放对账")
@SecurityRequirement(name = "serviceToken")
public class QuotaController {

    private final QuotaService quota;

    public QuotaController(QuotaService quota) {
        this.quota = quota;
    }

    @Operation(operationId = "getQuotaSnapshot", summary = "查询主体额度")
    @GetMapping("/me/quota")
    public QuotaSnapshot quota(@RequestParam(defaultValue = "USER") String scopeKind, @RequestParam String subjectId) {
        return quota.snapshot(scopeKind, subjectId);
    }

    @Operation(operationId = "listUsage", summary = "查询主体用量明细")
    @GetMapping("/me/usage")
    public UsageList usage(
            @RequestParam(defaultValue = "USER") String scopeKind,
            @RequestParam String subjectId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String projectId) {
        return new UsageList(quota.usage(scopeKind, subjectId, limit, model, projectId));
    }

    @Operation(operationId = "grantQuota", summary = "分配或调整额度")
    @PostMapping("/admin/quota-grants")
    public QuotaGrantResult grant(
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody QuotaGrantRequest request) {
        return quota.grant(new GrantRequest(
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

    @Operation(operationId = "updateQuotaAccountState", summary = "停用或恢复额度账户")
    @PostMapping("/admin/quota-accounts/{accountId}/state")
    public QuotaAccountStateResult state(
            @PathVariable UUID accountId,
            @RequestParam String state,
            @RequestParam(defaultValue = "system") String actorId,
            @RequestParam(defaultValue = "") String reason) {
        return quota.suspend(accountId, state, actorId, reason);
    }

    @Operation(operationId = "adjustUsage", summary = "追加用量调整")
    @PostMapping("/admin/usage-adjustments")
    public UsageAdjustmentResult adjust(
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody AdjustmentRequest request) {
        return quota.adjust(
                request.modelCallId(), request.deltaUnits(), request.reason(), request.operatorId(), idempotencyKey);
    }

    @Operation(operationId = "reconcileRelease", summary = "对不确定调用做释放对账")
    @PostMapping("/admin/calls/{reservationId}/reconcile-release")
    public ReconcileReleaseResult reconcile(@PathVariable UUID reservationId) {
        quota.reconcileRelease(reservationId);
        return new ReconcileReleaseResult(reservationId, "RELEASED");
    }
}
