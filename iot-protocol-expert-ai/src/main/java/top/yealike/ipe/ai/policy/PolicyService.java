package top.yealike.ipe.ai.policy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import top.yealike.ipe.ai.identity.UserIdentity;
import top.yealike.ipe.ai.support.JsonSupport;

/**
 * 授权策略：角色到 Agent / Tool / 站点范围的映射。
 *
 * <p>子 Agent 的可用工具 = 用户授权 ∩ Agent 授权 ∩ 当前数据范围 ∩ 环境策略。策略缺失时默认拒绝。
 */
@Service
public class PolicyService {

    private static final String ALL = "*";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PolicyService.class);

    private final JdbcTemplate jdbc;
    private final String environment;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(new Snapshot(List.of(), "v1"));

    public PolicyService(JdbcTemplate jdbc, top.yealike.ipe.ai.config.AiProperties properties) {
        this.jdbc = jdbc;
        this.environment = properties.environment();
    }

    public record Rule(String role, Set<String> agentKeys, Set<String> toolNames, Set<String> stationScope) {}

    public record Snapshot(List<Rule> rules, String version) {}

    public record Decision(boolean allowed, String reason, String policyVersion) {}

    private volatile long lastAttemptMillis;

    /**
     * 重新加载策略。数据库不可用时保留空策略（等价于全部拒绕）并记录错误，
     * 不让一个下游故障拖垮整个服务启动；恢复后由管理接口或下一次调用重试。
     */
    public void reload() {
        lastAttemptMillis = System.currentTimeMillis();
        try {
            List<Rule> rules = jdbc.query(
                    "select * from ai_permission_policy where enabled = true and environment in ('*', ?)",
                    (rs, rowNum) -> new Rule(
                            rs.getString("role"),
                            Set.copyOf(JsonSupport.readStringList(rs.getString("agent_keys"))),
                            Set.copyOf(JsonSupport.readStringList(rs.getString("tool_names"))),
                            Set.copyOf(JsonSupport.readStringList(rs.getString("station_scope")))), environment);
            String version = jdbc
                    .queryForList(
                            "select policy_version from ai_permission_policy order by updated_at desc limit 1",
                            String.class)
                    .stream()
                    .findFirst()
                    .orElse("v1");
            snapshot.set(new Snapshot(List.copyOf(rules), version));
        } catch (RuntimeException exception) {
            log.warn("ai permission policy load failed, denying all agent and tool access", exception);
            snapshot.set(new Snapshot(List.of(), "unavailable"));
        }
    }

    private Snapshot current() {
        Snapshot value = snapshot.get();
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

    public List<Rule> rulesFor(UserIdentity identity) {
        List<Rule> matched = new ArrayList<>();
        for (Rule rule : current().rules()) {
            if (identity.hasRole(rule.role())) {
                matched.add(rule);
            }
        }
        return matched;
    }

    public Decision canUseAgent(UserIdentity identity, String agentKey) {
        List<Rule> rules = rulesFor(identity);
        if (rules.isEmpty()) {
            return new Decision(false, "当前账号未配置 AI 使用策略", current().version());
        }
        boolean allowed = rules.stream().anyMatch(rule -> matches(rule.agentKeys(), agentKey));
        return new Decision(allowed, allowed ? "OK" : "无权使用该 Agent", current().version());
    }

    public Decision canUseTool(UserIdentity identity, String agentKey, String toolName) {
        List<Rule> rules = rulesFor(identity);
        if (rules.isEmpty()) {
            return new Decision(false, "当前账号未配置 AI 使用策略", current().version());
        }
        boolean allowed = rules.stream()
                .anyMatch(rule -> matches(rule.agentKeys(), agentKey) && matches(rule.toolNames(), toolName));
        return new Decision(allowed, allowed ? "OK" : "无权调用该工具", current().version());
    }

    /** 站点范围：* 表示不限；空集合表示不允许访问任何业务数据。 */
    public Set<String> stationScope(UserIdentity identity) {
        Set<String> scope = new LinkedHashSet<>();
        for (Rule rule : rulesFor(identity)) {
            scope.addAll(rule.stationScope());
        }
        return scope;
    }

    public boolean unlimitedStationScope(UserIdentity identity) {
        return stationScope(identity).contains(ALL);
    }

    /** 供工具调用前取一个可传给 ops 的范围表达式，业务侧会再校验一次。 */
    public String stationScopeHeader(UserIdentity identity) {
        Set<String> scope = stationScope(identity);
        if (scope.isEmpty()) {
            return "";
        }
        if (scope.contains(ALL)) {
            return ALL;
        }
        return scope.stream().sorted().collect(java.util.stream.Collectors.joining(","));
    }

    public List<String> allowedAgents(UserIdentity identity) {
        List<String> result = new ArrayList<>();
        for (Rule rule : rulesFor(identity)) {
            result.addAll(rule.agentKeys());
        }
        return result;
    }

    public Optional<Rule> ruleFor(String role) {
        return current().rules().stream()
                .filter(rule -> rule.role().equals(role))
                .findFirst();
    }

    private static boolean matches(Set<String> candidates, String value) {
        return candidates.contains(ALL) || candidates.contains(value);
    }

    public Map<String, Object> describe() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Rule rule : current().rules()) {
            rows.add(Map.of(
                    "role",
                    rule.role(),
                    "agentKeys",
                    rule.agentKeys(),
                    "toolNames",
                    rule.toolNames(),
                    "stationScope",
                    rule.stationScope()));
        }
        return Map.of("version", current().version(), "rules", rows);
    }
}
