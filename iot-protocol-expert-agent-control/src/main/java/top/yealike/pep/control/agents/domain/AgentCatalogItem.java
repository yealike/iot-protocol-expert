package top.yealike.pep.control.agents.domain;

import java.util.List;

/** 控制面领域记录。 */
public record AgentCatalogItem(
        String agentKey,
        String version,
        String name,
        String description,
        String status,
        int toolCount,
        int skillCount,
        List<String> skills,
        boolean healthy,
        boolean healthVerified,
        List<String> capabilityTags,
        List<AgentCatalogDelegate> delegates) {}
