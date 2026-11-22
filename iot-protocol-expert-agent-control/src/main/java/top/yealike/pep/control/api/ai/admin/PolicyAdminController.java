package top.yealike.pep.control.api.ai.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.control.access.policy.PolicyService;

/** Agent 与工具使用策略的只读查看和手工重载。 */
@RestController
@RequestMapping("/api/ai/v1")
@Tag(name = "AI 权限策略", description = "角色到 Agent/工具授权映射")
public class PolicyAdminController {

    private final PolicyService policy;

    public PolicyAdminController(PolicyService policy) {
        this.policy = policy;
    }

    @GetMapping("/admin/policies")
    @Operation(summary = "查看当前权限策略")
    @PreAuthorize("hasAuthority('ai:admin')")
    public Map<String, Object> policies() {
        return policy.describe();
    }

    @PostMapping("/admin/policies/reload")
    @Operation(summary = "重新加载权限策略")
    @PreAuthorize("hasAuthority('ai:admin')")
    public Map<String, Object> reload() {
        policy.reload();
        return Map.of("ok", true, "version", policy.version());
    }
}
