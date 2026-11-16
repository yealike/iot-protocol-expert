package top.yealike.ipe.agent.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import top.yealike.ipe.agent.runtime.config.RuntimeProperties;
import top.yealike.ipe.ai.contract.AgentManifest;
import top.yealike.ipe.ai.contract.McpDescriptor;
import top.yealike.ipe.ai.contract.McpToolDescriptor;
import top.yealike.ipe.ai.contract.McpToolSchemas;

/** 单测共享的纯数据构造器；不启动 Spring，也不连接 Nacos/Control。 */
public final class TestFixtures {

    private TestFixtures() {}

    public static RuntimeProperties properties() {
        return properties("http://127.0.0.1:19083", "test-secret");
    }

    public static RuntimeProperties properties(String controlBaseUrl, String serviceKey) {
        return new RuntimeProperties(
                19084,
                controlBaseUrl,
                "runtime",
                "runtime",
                serviceKey,
                "",
                "control",
                "127.0.0.1",
                new RuntimeProperties.Nacos("127.0.0.1:8848", "", "", "public", "IPE_AI_COMPONENTS", false),
                new RuntimeProperties.Run(4, 8, 12, 32, 300_000L, 5_000L, 10_000L),
                new RuntimeProperties.Mcp(3_000, 10_000, 20_000, 32, 300, 4, 32, 65_536),
                new RuntimeProperties.Skills(12_000));
    }

    public static McpToolDescriptor mcpTool() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("order_no", Map.of("type", "string"));
        properties.put("_ipe_context", Map.of("type", "string"));
        schema.put("properties", properties);
        schema.put("required", List.of("order_no"));
        schema.put("additionalProperties", false);
        return new McpToolDescriptor(
                "get_order_detail", "查询订单", schema, Map.of(), Map.of(), McpToolSchemas.digest(schema), true);
    }

    public static McpDescriptor mcpDescriptor() {
        McpToolDescriptor tool = mcpTool();
        return new McpDescriptor(
                "ops",
                "1.0.0",
                "STREAMABLE_HTTP",
                "http://127.0.0.1:19090/mcp",
                null,
                0L,
                top.yealike.ipe.ai.contract.ComponentDigests.mcpCatalog(List.of(tool)),
                List.of(tool),
                Map.of("contextMode", "OPS"));
    }

    public static AgentManifest manifest(UUID releaseId, boolean requiredMcp, List<AgentManifest.SkillBinding> skills) {
        McpDescriptor descriptor = mcpDescriptor();
        McpToolDescriptor tool = descriptor.tools().getFirst();
        AgentManifest.McpBinding binding = new AgentManifest.McpBinding(
                "ops",
                "1.0.0",
                requiredMcp,
                descriptor.catalogDigest(),
                List.of(new AgentManifest.McpToolBinding(tool.name(), "ops_get_order_detail", tool.schemaDigest())));
        return new AgentManifest(
                "1.0",
                "protocol-expert",
                "1.0.0",
                "协议排障专家",
                "你是协议排障专家。",
                new AgentManifest.ModelSpec(
                        "deepseek-default",
                        "OPENAI_COMPAT",
                        "https://api.deepseek.com",
                        "deepseek-chat",
                        "model-key",
                        1L,
                        Map.of("temperature", 0.1, "maxTokens", 1024)),
                skills,
                List.of(binding),
                new AgentManifest.Budget(6, 12, 120_000L),
                new AgentManifest.ExecutionPolicy(false, "ERROR"));
    }

    public static AgentManifest manifestWithoutMcp(List<AgentManifest.SkillBinding> skills) {
        AgentManifest base = manifest(UUID.randomUUID(), true, skills);
        return new AgentManifest(
                base.schemaVersion(),
                base.agentKey(),
                base.version(),
                base.displayName(),
                base.systemPrompt(),
                base.model(),
                base.skills(),
                List.of(),
                base.budget(),
                base.executionPolicy());
    }
}
