package top.yealike.pep.control.agents.domain;

import java.util.List;

/** Agent 草稿、绑定与发布版本详情。 */
public record AgentDetailView(
        AgentComponentView agent,
        List<AgentMcpBinding> mcpBindings,
        List<AgentSkillBinding> skillBindings,
        List<AgentRelease> releases) {

    public AgentDetailView {
        mcpBindings = mcpBindings == null ? List.of() : List.copyOf(mcpBindings);
        skillBindings = skillBindings == null ? List.of() : List.copyOf(skillBindings);
        releases = releases == null ? List.of() : List.copyOf(releases);
    }

    public static AgentDetailView from(AgentDetail detail) {
        return new AgentDetailView(
                AgentComponentView.from(detail.agent()),
                detail.mcpBindings(),
                detail.skillBindings(),
                detail.releases());
    }
}
