package top.yealike.pep.control.agents.domain;

import java.util.UUID;

/** 控制面领域记录。 */
public record AgentSkillBinding(
        UUID skillReleaseId,
        String componentKey,
        String version,
        int order,
        String loadMode,
        String digest,
        String name,
        String description) {}
