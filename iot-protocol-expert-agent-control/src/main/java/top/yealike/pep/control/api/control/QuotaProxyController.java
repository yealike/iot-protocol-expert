package top.yealike.pep.control.api.control;

import io.swagger.v3.oas.annotations.Operation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import top.yealike.pep.control.access.domain.QuotaSubject;
import top.yealike.pep.control.access.mapper.QuotaSubjectMapper;
import top.yealike.pep.control.agents.model.ModelGatewayClient;
import top.yealike.pep.control.api.control.dto.GrantRequest;
import top.yealike.pep.control.config.ModelGatewayProperties;
import top.yealike.pep.control.support.AiExceptions;

/**
 * 用户额度查询与管理代理。浏览器只与控制服务交互，扣费主体由服务端会话确定，
 * 不接受客户端自行提交的 USER 主体。
 */
@RestController
@RequestMapping(path = "/api/control/v1", produces = "application/json")
public class QuotaProxyController {

    private final ModelGatewayClient gateway;
    private final AccessService access;
    private final QuotaSubjectMapper subjects;
    private final ModelGatewayProperties modelGatewayProperties;

    public QuotaProxyController(
            ModelGatewayClient gateway,
            AccessService access,
            QuotaSubjectMapper subjects,
            ModelGatewayProperties modelGatewayProperties) {
        this.gateway = gateway;
        this.access = access;
        this.subjects = subjects;
        this.modelGatewayProperties = modelGatewayProperties;
    }

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

    @Operation(summary = "按名称或账号搜索额度分配对象")
    @GetMapping("/admin/quota-subjects")
    public List<QuotaSubject> quotaSubjects(
            @RequestParam(defaultValue = "USER") String scopeKind, @RequestParam(defaultValue = "") String query) {
        if (!access.hasPlatformPermission(CurrentUser.require(), "quota:grant")) {
            access.requirePlatformPermission("usage:read");
        }
        String search = query.strip();
        if (search.length() > 120) {
            throw AiExceptions.badRequest("搜索内容最多 120 个字符");
        }
        return switch (scopeKind) {
            case "USER" -> subjects.findQuotaUsers(search);
            case "SERVICE_ACCOUNT" -> serviceAccounts(search);
            default -> throw AiExceptions.badRequest("额度对象类型无效");
        };
    }

    private List<QuotaSubject> serviceAccounts(String search) {
        List<QuotaSubject> matches = subjects.findQuotaServiceAccounts(search);
        String evaluator = modelGatewayProperties.getEvaluatorSubject();
        String name = "模型评测";
        String normalized = search.toLowerCase(Locale.ROOT);
        if (evaluator == null
                || evaluator.isBlank()
                || (!name.contains(search)
                        && !evaluator.toLowerCase(Locale.ROOT).contains(normalized))) {
            return matches;
        }
        List<QuotaSubject> results = new ArrayList<>(matches.size() + 1);
        results.add(new QuotaSubject(evaluator, evaluator, name, "ACTIVE", "系统服务"));
        matches.stream().filter(subject -> !evaluator.equals(subject.id())).forEach(results::add);
        return results;
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

    @Operation(summary = "对不确定调用做释放对账")
    @PostMapping("/admin/calls/{reservationId}/reconcile-release")
    public Map<String, Object> reconcile(@PathVariable UUID reservationId) {
        access.requirePlatformPermission("quota:grant");
        return gateway.reconcileRelease(reservationId);
    }
}
