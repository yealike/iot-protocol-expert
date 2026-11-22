package top.yealike.pep.control.agents.domain;

import java.util.List;

public record AgentDetail(
        AgentComponent agent,
        List<AgentMcpBinding> mcpBindings,
        List<AgentSkillBinding> skillBindings,
        List<AgentRelease> releases) {}
