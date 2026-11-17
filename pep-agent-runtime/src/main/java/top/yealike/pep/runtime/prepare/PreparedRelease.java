package top.yealike.pep.runtime.prepare;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import top.yealike.pep.runtime.contract.AgentManifest;
import top.yealike.pep.runtime.contract.McpDescriptor;
import top.yealike.pep.runtime.contract.SkillBundle;

/** Runtime 内的不可变发布计划；prepare 成功后只读。 */
public record PreparedRelease(
        UUID releaseId,
        String manifestDigest,
        long generation,
        UUID publicationId,
        AgentManifest manifest,
        Map<String, McpDescriptor> mcps,
        Map<String, SkillBundle> skills,
        List<PreparedTool> tools,
        List<String> warnings,
        Instant preparedAt) {

    public PreparedRelease {
        mcps = mcps == null ? Map.of() : Map.copyOf(mcps);
        skills = skills == null ? Map.of() : Map.copyOf(skills);
        tools = tools == null ? List.of() : List.copyOf(tools);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public Map<String, PreparedTool> toolByAlias() {
        Map<String, PreparedTool> result = new LinkedHashMap<>();
        tools.forEach(tool -> result.put(tool.alias(), tool));
        return Map.copyOf(result);
    }

    public SkillBundle skill(String key) {
        return skills.get(key);
    }
}
