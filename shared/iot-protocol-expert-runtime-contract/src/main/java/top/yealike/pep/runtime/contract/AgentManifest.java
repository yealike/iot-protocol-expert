package top.yealike.pep.runtime.contract;

import java.util.List;

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
        ExecutionPolicy executionPolicy,
        String description,
        List<String> capabilityTags,
        List<A2aCapability> a2aCapabilities,
        List<DelegateBinding> delegates,
        DelegationPolicy delegationPolicy) {

    public static final String V1 = "ipe.agent/v1";
    public static final String V2 = "ipe.agent/v2";

    public AgentManifest {
        skills = skills == null ? List.of() : List.copyOf(skills);
        mcp = mcp == null ? List.of() : List.copyOf(mcp);
        capabilityTags = capabilityTags == null ? List.of() : List.copyOf(capabilityTags);
        a2aCapabilities = a2aCapabilities == null ? List.of() : List.copyOf(a2aCapabilities);
        delegates = delegates == null ? List.of() : List.copyOf(delegates);
        if (!V2.equals(schemaVersion)
                && (!delegates.isEmpty()
                        || delegationPolicy != null
                        || description != null
                        || !capabilityTags.isEmpty()
                        || !a2aCapabilities.isEmpty())) {
            throw new IllegalArgumentException("只有 v2 发布清单支持委派和能力声明");
        }
    }

    /** 保留旧版本清单的构造契约。 */
    public AgentManifest(
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
        this(
                schemaVersion,
                agentKey,
                version,
                displayName,
                systemPrompt,
                model,
                skills,
                mcp,
                budget,
                executionPolicy,
                null,
                List.of(),
                List.of(),
                List.of(),
                null);
    }

    public record ModelSpec(
            String profileKey,
            String providerType,
            String baseUrl,
            String modelName,
            String credentialRef,
            long secretRevision,
            ModelOptions options) {}

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
