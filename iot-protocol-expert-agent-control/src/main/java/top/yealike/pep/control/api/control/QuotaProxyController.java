package top.yealike.pep.control.api.control;

import io.swagger.v3.oas.annotations.Operation;
import java.util.LinkedHashMap;
import java.util.List;
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
import top.yealike.pep.control.access.AccessService;
import top.yealike.pep.control.access.CurrentUser;
import top.yealike.pep.control.agents.model.ModelGatewayClient;

/**
 * 用户额度查询与管理代理。浏览器只与控制服务交互，扣费主体由服务端会话确定，
 * 不接受客户端自行提交的 USER 主体。
 */
@RestController
@RequestMapping(path = "/api/control/v1", produces = "application/json")
public class QuotaProxyController {

    private final ModelGatewayClient gateway;
    private final AccessService access;

    public QuotaProxyController(ModelGatewayClient gateway, AccessService access) {
        this.gateway = gateway;
        this.access = access;
    }

    public record GrantRequest(
            String scopeKind,
            String subjectId,
            String mode,
            long limitUnits,
            Long durationSeconds,
            String policyKey,
            String reason,
            String actorId) {}

    public record AdjustmentRequest(UUID modelCallId, long deltaUnits, String reason, String operatorId) {}

    @Operation(summary = "查询自己的模型额度")
    @GetMapping("/me/quota")
    public Map<String, Object> myQuota() {
        return gateway.quota("USER", CurrentUser.require().id());
    }

    @Operation(summary = "查询自己的模型用量")
    @GetMapping("/me/usage")
    public List<Map<String, Object>> myUsage(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String projectId) {
        return gateway.usage("USER", CurrentUser.require().id(), limit, model, projectId);
    }

    @Operation(summary = "按主体额度、策略、冻结和待对账状态")
    @GetMapping("/admin/quota-accounts")
    public Map<String, Object> account(
            @RequestParam(defaultValue = "USER") String scopeKind, @RequestParam String subjectId) {
        access.requirePlatformPermission("usage:read");
        return gateway.quota(scopeKind, subjectId);
    }

    @Operation(summary = "分配或调整模型额度")
    @PostMapping("/admin/quota-grants")
    public Map<String, Object> grant(
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody GrantRequest request) {
        access.requirePlatformPermission("quota:grant");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeKind", request.scopeKind());
        body.put("subjectId", request.subjectId());
        body.put("mode", request.mode());
        body.put("limitUnits", request.limitUnits());
        if (request.durationSeconds() != null) {
            body.put("durationSeconds", request.durationSeconds());
        }
        body.put("policyKey", request.policyKey() == null ? "" : request.policyKey());
        body.put("reason", request.reason() == null ? "" : request.reason());
        body.put("actorId", request.actorId() == null ? "admin" : request.actorId());
        return gateway.grant(body, idempotencyKey);
    }

    @Operation(summary = "停用或恢复额度账户")
    @PostMapping("/admin/quota-accounts/{accountId}/state")
    public Map<String, Object> state(
            @PathVariable UUID accountId,
            @RequestParam String state,
            @RequestParam(defaultValue = "system") String actorId,
            @RequestParam(defaultValue = "") String reason) {
        access.requirePlatformPermission("quota:grant");
        return gateway.setAccountState(accountId, state, actorId, reason);
    }

    @Operation(summary = "追加用量调整")
    @PostMapping("/admin/usage-adjustments")
    public Map<String, Object> adjust(
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody AdjustmentRequest request) {
        access.requirePlatformPermission("quota:grant");
        return gateway.adjustUsage(
                Map.of(
                        "modelCallId",
                        request.modelCallId(),
                        "deltaUnits",
                        request.deltaUnits(),
                        "reason",
                        request.reason() == null ? "" : request.reason(),
                        "operatorId",
                        request.operatorId() == null ? "admin" : request.operatorId()),
                idempotencyKey);
    }

    @Operation(summary = "对不确定调用做释放对账")
    @PostMapping("/admin/calls/{reservationId}/reconcile-release")
    public Map<String, Object> reconcile(@PathVariable UUID reservationId) {
        access.requirePlatformPermission("quota:grant");
        return gateway.reconcileRelease(reservationId);
    }
}
