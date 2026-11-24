package top.yealike.pep.control.agents;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.pep.control.access.policy.PolicyService;
import top.yealike.pep.control.agents.domain.AgentTeam;
import top.yealike.pep.control.agents.store.AgentStore;
import top.yealike.pep.control.api.ai.admin.dto.AgentTeamRequest;
import top.yealike.pep.control.api.ai.admin.dto.DelegateBindingRequest;
import top.yealike.pep.control.audit.AiAuditLogMapper;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.runtime.contract.DelegateBinding;
import top.yealike.pep.runtime.contract.UserIdentity;

/** 管理 Agent 草稿的能力声明和精确版本绑定。 */
@Service
public class AgentTeamService {

    private final AgentStore agents;
    private final PolicyService policy;
    private final AiAuditLogMapper audit;

    public AgentTeamService(AgentStore agents, PolicyService policy, AiAuditLogMapper audit) {
        this.agents = agents;
        this.policy = policy;
        this.audit = audit;
    }

    /** 查询 Agent 草稿委派配置。 */
    public AgentTeam get(UUID agentId) {
        agents.find(agentId).orElseThrow(() -> AiExceptions.notFound("Agent 不存在"));
        return agents.team(agentId);
    }

    /** 保存草稿并按照现有草稿策略递增修订号。 */
    @Transactional(rollbackFor = Exception.class)
    public AgentTeam save(UUID agentId, UserIdentity actor, AgentTeamRequest request) {
        var source = agents.lock(agentId).orElseThrow(() -> AiExceptions.notFound("Agent 不存在"));
        if (source.revision() != request.expectedRevision()) {
            throw AiExceptions.conflict("REVISION_CONFLICT", "Agent 已被其他请求修改，请刷新后重试");
        }
        if (request.delegates().stream()
                        .map(DelegateBindingRequest::alias)
                        .distinct()
                        .count()
                != request.delegates().size()) {
            throw AiExceptions.badRequest("委派别名不能重复");
        }
        List<DelegateBinding> delegates = request.delegates().stream()
                .map(binding -> resolve(agentId, actor, binding))
                .toList();
        AgentTeam team = new AgentTeam(
                request.capabilityTags(), request.a2aCapabilities(), delegates, request.delegationPolicy());
        if (!agents.replaceTeam(agentId, request.expectedRevision(), team)) {
            throw AiExceptions.conflict("REVISION_CONFLICT", "Agent 已被其他请求修改，请刷新后重试");
        }
        audit.auditDelegation(actor.actor(), "AGENT_DELEGATES_UPDATED", "AGENT", agentId.toString(), "SUCCESS", null);
        return agents.team(agentId);
    }

    private DelegateBinding resolve(UUID agentId, UserIdentity actor, DelegateBindingRequest binding) {
        var release = agents.findRelease(binding.targetReleaseId())
                .filter(item -> "ACTIVE".equals(item.state()))
                .orElseThrow(() -> AiExceptions.conflict("TARGET_RELEASE_UNAVAILABLE", "目标 Agent 版本不可用"));
        if (!policy.canUseAgent(actor, release.agentKey()).allowed()) {
            throw AiExceptions.forbidden("USER_AGENT_FORBIDDEN", "当前账号无权使用目标 Agent");
        }
        if (agentId.equals(release.agentId())) {
            throw AiExceptions.unprocessable("DELEGATE_SELF_REFERENCE", "不能委派给自身的发布版本");
        }
        return new DelegateBinding(
                binding.alias(),
                release.agentKey(),
                release.id(),
                release.version(),
                release.manifestDigest(),
                binding.description().strip(),
                binding.whenToUse(),
                binding.allowedTools(),
                binding.required(),
                binding.maxCalls(),
                binding.timeoutMs(),
                binding.order());
    }
}
