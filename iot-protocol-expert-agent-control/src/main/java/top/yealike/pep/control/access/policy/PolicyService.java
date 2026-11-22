package top.yealike.pep.control.access.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import top.yealike.pep.control.access.policy.domain.Decision;
import top.yealike.pep.control.access.policy.domain.PolicyRule;
import top.yealike.pep.control.access.policy.domain.PolicySnapshot;
import top.yealike.pep.control.access.policy.mapper.PermissionPolicyMapper;
import top.yealike.pep.runtime.contract.UserIdentity;

/**
 * 授权策略：角色到 Agent / Tool 的映射。
 *
 * <p>子 Agent 的可用工具 = 用户授权 ∩ Agent 授权 ∩ 当前数据范围 ∩ 环境策略。策略缺失时默认拒绝。
 */
@Service
public class PolicyService {

    private static final String ALL = "*";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PolicyService.class);

    private final PermissionPolicyMapper mapper;
    private final String environment;
    private final AtomicReference<PolicySnapshot> snapshot = new AtomicReference<>(new PolicySnapshot(List.of(), "v1"));

    public PolicyService(
            PermissionPolicyMapper mapper, top.yealike.pep.control.config.properties.AiProperties properties) {
        this.mapper = mapper;
        this.environment = properties.environment();
    }

    private volatile long lastAttemptMillis;

    /**
     * 重新加载策略。数据库不可用时保留空策略（等价于全部拒绕）并记录错误，
     * 不让一个下游故障拖垮整个服务启动；恢复后由下一次调用重试。
     */
    public void reload() {
        lastAttemptMillis = System.currentTimeMillis();
        try {
            List<PolicyRule> rules = mapper.selectEnabled(environment);
            String version = mapper.selectLatestVersion().stream().findFirst().orElse("v1");
            snapshot.set(new PolicySnapshot(List.copyOf(rules), version));
        } catch (RuntimeException exception) {
            log.warn("ai permission policy load failed, denying all agent and tool access", exception);
            snapshot.set(new PolicySnapshot(List.of(), "unavailable"));
        }
    }

    private PolicySnapshot current() {
        PolicySnapshot value = snapshot.get();
        // 空策略可能是“尚未加载”或“加载失败”，限频重试，避免每个请求都打数据库
        if (value.rules().isEmpty() && System.currentTimeMillis() - lastAttemptMillis > 10_000) {
            reload();
            return snapshot.get();
        }
        return value;
    }

    public String version() {
        return current().version();
    }

    public List<PolicyRule> rulesFor(UserIdentity identity) {
        List<PolicyRule> matched = new ArrayList<>();
        for (PolicyRule rule : current().rules()) {
            if (identity.hasRole(rule.role()) || identity.hasRole(currentRole(rule.role()))) {
                matched.add(rule);
            }
        }
        return matched;
    }

    /** 旧策略沿用 ADMIN/OPS，账号角色已由 IAM 统一为当前名称；权限范围仍使用原策略。 */
    private static String currentRole(String role) {
        return switch (role) {
            case "ADMIN" -> "PLATFORM_ADMIN";
            case "OPS" -> "OPERATOR";
            default -> role;
        };
    }

    public Decision canUseAgent(UserIdentity identity, String agentKey) {
        List<PolicyRule> rules = rulesFor(identity);
        if (rules.isEmpty()) {
            return new Decision(false, "当前账号未配置 AI 使用策略", current().version());
        }
        boolean allowed = rules.stream().anyMatch(rule -> matches(rule.agentKeys(), agentKey));
        return new Decision(allowed, allowed ? "OK" : "无权使用该 Agent", current().version());
    }

    public Decision canUseTool(UserIdentity identity, String agentKey, String toolName) {
        List<PolicyRule> rules = rulesFor(identity);
        if (rules.isEmpty()) {
            return new Decision(false, "当前账号未配置 AI 使用策略", current().version());
        }
        boolean allowed = rules.stream()
                .anyMatch(rule -> matches(rule.agentKeys(), agentKey) && matches(rule.toolNames(), toolName));
        return new Decision(allowed, allowed ? "OK" : "无权调用该工具", current().version());
    }

    private static boolean matches(Set<String> candidates, String value) {
        return candidates.contains(ALL) || candidates.contains(value);
    }
}
