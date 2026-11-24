package top.yealike.pep.control.agents.domain;

import java.util.List;
import top.yealike.pep.runtime.contract.A2aCapability;
import top.yealike.pep.runtime.contract.DelegateBinding;
import top.yealike.pep.runtime.contract.DelegationPolicy;

/** Agent 草稿的能力与委派配置。 */
public record AgentTeam(
        List<String> capabilityTags,
        List<A2aCapability> a2aCapabilities,
        List<DelegateBinding> delegates,
        DelegationPolicy delegationPolicy) {

    public static final AgentTeam EMPTY = new AgentTeam(List.of(), List.of(), List.of(), null);
}
