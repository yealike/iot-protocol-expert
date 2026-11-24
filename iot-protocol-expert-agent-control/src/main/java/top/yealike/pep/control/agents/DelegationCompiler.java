package top.yealike.pep.control.agents;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import top.yealike.pep.control.agents.domain.AgentComponent;
import top.yealike.pep.control.agents.domain.AgentRelease;
import top.yealike.pep.control.agents.domain.AgentTeam;
import top.yealike.pep.control.agents.domain.CompileIssue;
import top.yealike.pep.control.agents.store.AgentStore;
import top.yealike.pep.runtime.contract.DelegateBinding;
import top.yealike.pep.runtime.contract.DelegationPolicy;
import top.yealike.pep.runtime.contract.ManifestDigests;

/** 校验委派草稿及其已冻结的目标发布图。 */
final class DelegationCompiler {

    private static final int MAX_CAPABILITIES = 16;
    private static final int MIN_TIMEOUT_MS = 5_000;

    private DelegationCompiler() {}

    static AgentTeam compile(AgentStore agents, AgentComponent draft, long timeout, List<CompileIssue> errors) {
        AgentTeam team = Objects.requireNonNullElse(agents.team(draft.id()), AgentTeam.EMPTY);
        DelegationPolicy policy = team.delegationPolicy();
        if (policy == null && !team.delegates().isEmpty()) {
            policy = DelegationPolicy.defaults(timeout);
        }
        if (policy != null && !policy.validFor(timeout)) {
            errors.add(new CompileIssue("INVALID_DELEGATION_POLICY", "委派策略超出一期支持的限额", "delegationPolicy"));
        }
        if (team.capabilityTags().size() > MAX_CAPABILITIES
                || !validList(team.capabilityTags(), 40)
                || team.a2aCapabilities().size() > MAX_CAPABILITIES) {
            errors.add(new CompileIssue("INVALID_A2A_CAPABILITIES", "能力或标签数量、长度超限", "a2aCapabilities"));
        }
        Set<String> capabilityIds = new HashSet<>();
        for (var capability : team.a2aCapabilities()) {
            if (!validText(capability.id(), 128)
                    || !capabilityIds.add(capability.id())
                    || !validText(capability.name(), 128)
                    || !validText(capability.description(), 512)
                    || !validList(capability.tags(), 40)
                    || !validList(capability.examples(), 512)
                    || !validList(capability.inputModes(), 80)
                    || !validList(capability.outputModes(), 80)) {
                errors.add(new CompileIssue("INVALID_A2A_CAPABILITIES", "能力声明无效或能力 ID 重复", "a2aCapabilities"));
            }
        }
        if (team.delegates().size() > DelegationPolicy.MAX_DELEGATES) {
            issue(errors, "DELEGATE_LIMIT_EXCEEDED", "一个 Agent 最多绑定 8 个委派目标");
        }
        Set<String> aliases = new HashSet<>();
        for (DelegateBinding binding : team.delegates()) {
            if (!validText(binding.alias(), 40)
                    || !binding.alias().matches("[a-z][a-z0-9_]{1,39}")
                    || !aliases.add(binding.alias())
                    || !validText(binding.description(), 512)
                    || binding.maxCalls() < 1
                    || binding.maxCalls() > DelegationPolicy.HARD_MAX_CALLS
                    || binding.timeoutMs() < MIN_TIMEOUT_MS
                    || binding.timeoutMs() > timeout) {
                issue(errors, "INVALID_DELEGATE", "委派别名重复、说明无效或调用限额超出预算");
                continue;
            }
            if (draft.key().equals(binding.targetAgentKey())) {
                issue(errors, "DELEGATE_SELF_REFERENCE", "不能委派给自身的发布版本");
                continue;
            }
            validateTarget(agents, binding, new HashSet<>(Set.of(draft.key())), 1, errors);
        }
        return new AgentTeam(team.capabilityTags(), team.a2aCapabilities(), team.delegates(), policy);
    }

    private static void validateTarget(
            AgentStore agents, DelegateBinding binding, Set<String> path, int depth, List<CompileIssue> errors) {
        if (!path.add(binding.targetAgentKey())) {
            issue(errors, "DELEGATE_CYCLE", "委派链路存在闭环");
            return;
        }
        try {
            if (depth > DelegationPolicy.HARD_MAX_DEPTH) {
                issue(errors, "DELEGATION_DEPTH_EXCEEDED", "委派链路超过平台深度上限");
                return;
            }
            AgentRelease target = agents.findRelease(binding.targetReleaseId()).orElse(null);
            if (target == null
                    || !"ACTIVE".equals(target.state())
                    || target.manifest() == null
                    || !binding.targetAgentKey().equals(target.agentKey())
                    || !binding.targetVersion().equals(target.version())
                    || !binding.targetManifestDigest().equals(target.manifestDigest())
                    || !target.manifestDigest().equals(ManifestDigests.digest(target.manifest()))
                    || !agents.find(target.agentId())
                            .map(AgentComponent::enabled)
                            .orElse(false)) {
                issue(errors, "TARGET_RELEASE_UNAVAILABLE", "委派目标版本不可用：" + binding.alias());
                return;
            }
            Set<String> tools = new HashSet<>();
            target.manifest().mcp().forEach(component -> component.tools().forEach(tool -> tools.add(tool.alias())));
            if (!tools.containsAll(binding.allowedTools())) {
                issue(errors, "INVALID_DELEGATE_TOOLS", "委派工具必须来自目标版本：" + binding.alias());
            }
            for (DelegateBinding child : target.manifest().delegates()) {
                validateTarget(agents, child, path, depth + 1, errors);
            }
        } finally {
            path.remove(binding.targetAgentKey());
        }
    }

    private static boolean validText(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }

    private static boolean validList(List<String> values, int maxLength) {
        return values.size() <= MAX_CAPABILITIES && values.stream().allMatch(value -> validText(value, maxLength));
    }

    private static void issue(List<CompileIssue> errors, String code, String message) {
        errors.add(new CompileIssue(code, message, "delegates"));
    }
}
