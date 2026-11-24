package top.yealike.pep.control.runs.delegation;

import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;
import top.yealike.pep.control.access.policy.PolicyService;
import top.yealike.pep.control.agents.domain.AgentComponent;
import top.yealike.pep.control.agents.domain.AgentRelease;
import top.yealike.pep.control.agents.store.AgentStore;
import top.yealike.pep.control.runs.RunStatus;
import top.yealike.pep.control.runs.domain.Run;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.runtime.contract.DelegateBinding;
import top.yealike.pep.runtime.contract.ManifestDigests;
import top.yealike.pep.runtime.contract.UserIdentity;

/** 委派必须同时满足用户权限、父发布白名单和目标发布约束。 */
@Component
public class DelegationAuthorizer {

    private final AgentStore agents;
    private final PolicyService policy;

    public DelegationAuthorizer(AgentStore agents, PolicyService policy) {
        this.agents = agents;
        this.policy = policy;
    }

    /** 校验父 Run 仍由同一 Runtime 持有。 */
    public void requireParent(Run parent, long token, String instanceId) {
        Instant now = Instant.now();
        if (token <= 0
                || parent.executionToken() != token
                || instanceId == null
                || instanceId.isBlank()
                || !instanceId.equals(parent.runtimeInstanceId())
                || !instanceId.equals(parent.leaseOwner())
                || !RunStatus.RUNNING.equals(parent.status())
                || parent.leaseUntil() == null
                || !parent.leaseUntil().isAfter(now)
                || parent.deadlineAt() == null
                || !parent.deadlineAt().isAfter(now)) {
            throw AiExceptions.conflict("STALE_EXECUTION_TOKEN", "父 Run 执行权已失效");
        }
    }

    /** 校验用户权限与被冻结的别名。 */
    public DelegateBinding binding(AgentRelease source, String alias, UserIdentity user, Run parent) {
        if (!Objects.equals(parent.actor(), user.actor())
                || !Objects.equals(parent.actorAuthVersion(), user.authVersion())
                || !policy.canUseAgent(user, parent.agentKey()).allowed()) {
            throw AiExceptions.forbidden("USER_AGENT_FORBIDDEN", "当前账号无权发起委派");
        }
        if (!"ACTIVE".equals(source.state()) || !Objects.equals(source.manifestDigest(), parent.manifestDigest())) {
            throw AiExceptions.conflict("SOURCE_RELEASE_UNAVAILABLE", "父 Agent 发布版本不可用");
        }
        DelegateBinding binding = source.manifest().delegates().stream()
                .filter(item -> alias.equals(item.alias()))
                .findFirst()
                .orElseThrow(() -> AiExceptions.forbidden("DELEGATE_NOT_ALLOWED", "目标不在发布白名单内"));
        if (!policy.canUseAgent(user, binding.targetAgentKey()).allowed()) {
            throw AiExceptions.forbidden("USER_AGENT_FORBIDDEN", "当前账号无权使用目标 Agent");
        }
        return binding;
    }

    /** 核对精确目标版本及发布摘要。 */
    public AgentRelease target(DelegateBinding binding) {
        return agents.findRelease(binding.targetReleaseId())
                .filter(item -> "ACTIVE".equals(item.state())
                        && binding.targetAgentKey().equals(item.agentKey())
                        && binding.targetVersion().equals(item.version())
                        && binding.targetManifestDigest().equals(item.manifestDigest())
                        && item.manifestDigest().equals(ManifestDigests.digest(item.manifest()))
                        && agents.find(item.agentId())
                                .map(AgentComponent::enabled)
                                .orElse(false))
                .orElseThrow(() -> AiExceptions.conflict("TARGET_RELEASE_UNAVAILABLE", "目标 Agent 版本不可用"));
    }
}
