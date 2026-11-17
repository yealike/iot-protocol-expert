package top.yealike.pep.runtime.contract;

import java.util.List;
import java.util.Map;

/**
 * Agent 发布清单。清单只包含定位信息与稳定摘要，不包含运行入口、用户权限或密钥明文。
 */
public record AgentManifest(
        String schemaVersion,
        String agentKey,
        String version,
        String displayName,
        String systemPrompt,
        ModelSpec model,
        List<SkillBinding> skills,
        List<McpBinding> mcp,
        Budget budget,
        ExecutionPolicy executionPolicy) {

    public AgentManifest {
        skills = skills == null ? List.of() : List.copyOf(skills);
        mcp = mcp == null ? List.of() : List.copyOf(mcp);
    }

    public record ModelSpec(
            String profileKey,
            String providerType,
            String baseUrl,
            String modelName,
            String credentialRef,
            long secretRevision,
            Map<String, Object> options) {

        public ModelSpec {
            options = options == null ? Map.of() : Map.copyOf(options);
        }
    }

    public record SkillBinding(String key, String version, String digest, String loadMode, int order) {}

    public record McpBinding(
            String key, String version, boolean required, String catalogDigest, List<McpToolBinding> tools) {

        public McpBinding {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    public record McpToolBinding(String name, String alias, String schemaDigest) {}

    public record Budget(int maxIterations, int maxToolCalls, long runTimeoutMs) {}

    public record ExecutionPolicy(boolean scriptExecution, String unknownTool) {}
}
