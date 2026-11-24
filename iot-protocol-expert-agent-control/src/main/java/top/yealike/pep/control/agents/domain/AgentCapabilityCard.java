package top.yealike.pep.control.agents.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import top.yealike.pep.runtime.contract.A2aCapability;

/** 从已激活 Release 派生的内部能力卡片。 */
@Schema(description = "Agent 的版本化能力卡片")
public record AgentCapabilityCard(
        String name, String description, String version, List<String> capabilityTags, List<A2aCapability> skills) {}
