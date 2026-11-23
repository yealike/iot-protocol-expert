package top.yealike.pep.runtime.contract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent manifest 的规范摘要。
 *
 * <p>不能直接对 Java record 调用 {@link Digests#digest(Object)}：record 的 toString 不是规范 JSON。
 * 这里显式构造字段映射，Control 发布与 Runtime 校验共用同一实现。
 */
public final class ManifestDigests {

    private ManifestDigests() {}

    public static String digest(AgentManifest manifest) {
        return Digests.digest(canonical(manifest));
    }

    public static Map<String, Object> canonical(AgentManifest manifest) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", manifest.schemaVersion());
        value.put("agentKey", manifest.agentKey());
        value.put("version", manifest.version());
        value.put("displayName", manifest.displayName());
        value.put("systemPrompt", manifest.systemPrompt());
        value.put("model", model(manifest.model()));
        value.put("skills", skills(manifest.skills()));
        value.put("mcp", mcps(manifest.mcp()));
        value.put("budget", budget(manifest.budget()));
        value.put("executionPolicy", policy(manifest.executionPolicy()));
        return value;
    }

    private static Map<String, Object> model(AgentManifest.ModelSpec model) {
        if (model == null) {
            return Map.of();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("profileKey", model.profileKey());
        value.put("providerType", model.providerType());
        value.put("baseUrl", model.baseUrl());
        value.put("modelName", model.modelName());
        value.put("credentialRef", model.credentialRef());
        value.put("secretRevision", model.secretRevision());
        value.put(
                "options", model.options() == null ? Map.of() : model.options().asMap());
        return value;
    }

    private static List<Map<String, Object>> skills(List<AgentManifest.SkillBinding> skills) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (AgentManifest.SkillBinding skill : skills) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("key", skill.key());
            value.put("version", skill.version());
            value.put("digest", skill.digest());
            value.put("loadMode", skill.loadMode());
            value.put("order", skill.order());
            values.add(value);
        }
        return values;
    }

    private static List<Map<String, Object>> mcps(List<AgentManifest.McpBinding> mcps) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (AgentManifest.McpBinding mcp : mcps) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("key", mcp.key());
            value.put("version", mcp.version());
            value.put("required", mcp.required());
            value.put("catalogDigest", mcp.catalogDigest());
            List<Map<String, Object>> tools = new ArrayList<>();
            for (AgentManifest.McpToolBinding tool : mcp.tools()) {
                Map<String, Object> toolValue = new LinkedHashMap<>();
                toolValue.put("name", tool.name());
                toolValue.put("alias", tool.alias());
                toolValue.put("schemaDigest", tool.schemaDigest());
                tools.add(toolValue);
            }
            value.put("tools", tools);
            values.add(value);
        }
        return values;
    }

    private static Map<String, Object> budget(AgentManifest.Budget budget) {
        if (budget == null) {
            return Map.of();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("maxIterations", budget.maxIterations());
        value.put("maxToolCalls", budget.maxToolCalls());
        value.put("runTimeoutMs", budget.runTimeoutMs());
        return value;
    }

    private static Map<String, Object> policy(AgentManifest.ExecutionPolicy policy) {
        if (policy == null) {
            return Map.of();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("scriptExecution", policy.scriptExecution());
        value.put("unknownTool", policy.unknownTool());
        return value;
    }
}
