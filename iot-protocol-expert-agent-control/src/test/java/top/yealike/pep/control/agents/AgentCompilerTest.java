package top.yealike.pep.control.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.yealike.pep.control.agents.domain.AgentComponent;
import top.yealike.pep.control.agents.domain.CompileIssue;
import top.yealike.pep.control.agents.domain.CompileResult;
import top.yealike.pep.control.agents.domain.ModelProfile;
import top.yealike.pep.control.agents.store.AgentStore;
import top.yealike.pep.control.agents.store.CredentialStore;
import top.yealike.pep.control.agents.store.McpStore;
import top.yealike.pep.control.agents.store.ModelStore;
import top.yealike.pep.control.agents.store.SkillStore;
import top.yealike.pep.control.config.ModelGatewayProperties;

class AgentCompilerTest {

    private final AgentStore agents = mock(AgentStore.class);
    private final ModelStore models = mock(ModelStore.class);
    private final McpStore mcps = mock(McpStore.class);
    private final SkillStore skills = mock(SkillStore.class);
    private final CredentialStore credentials = mock(CredentialStore.class);
    private final AgentCompiler compiler =
            new AgentCompiler(agents, models, mcps, skills, credentials, new ModelGatewayProperties());

    @Test
    void acceptsMaximumSupportedTokensAndBudget() {
        UUID agentId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        when(models.find(modelId)).thenReturn(Optional.of(model(modelId, 131_072)));
        when(agents.skillBindings(agentId)).thenReturn(List.of());
        when(agents.mcpBindings(agentId)).thenReturn(List.of());

        CompileResult result = compiler.compile(
                agent(
                        agentId,
                        modelId,
                        Map.of("maxTokens", 131_072),
                        Map.of("maxIterations", 12, "maxToolCalls", 32, "runTimeoutMs", 300_000)),
                "1.0.0");

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.manifest().model().options().maxTokens()).isEqualTo(131_072);
    }

    @Test
    void rejectsValuesAboveCompilerLimits() {
        UUID agentId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        when(models.find(modelId)).thenReturn(Optional.of(model(modelId, 131_073)));
        when(agents.skillBindings(agentId)).thenReturn(List.of());
        when(agents.mcpBindings(agentId)).thenReturn(List.of());

        CompileResult result = compiler.compile(
                agent(
                        agentId,
                        modelId,
                        Map.of("maxTokens", 131_073),
                        Map.of("maxIterations", 13, "maxToolCalls", 33, "runTimeoutMs", 300_001)),
                "1.0.0");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors())
                .extracting(CompileIssue::code)
                .contains(
                        "MAX_TOKENS_OUT_OF_RANGE",
                        "BUDGET_ITERATIONS_EXCEEDED",
                        "BUDGET_TOOL_CALLS_EXCEEDED",
                        "BUDGET_TIMEOUT_EXCEEDED");
    }

    private static ModelProfile model(UUID id, int maxTokens) {
        Instant now = Instant.now();
        return new ModelProfile(
                id,
                "deepseek-default",
                "DeepSeek",
                "",
                "OPENAI_COMPAT",
                "https://api.deepseek.com",
                "deepseek-chat",
                null,
                null,
                Map.of("maxTokens", maxTokens, "temperature", 0.1),
                true,
                0,
                "tester",
                now,
                now);
    }

    private static AgentComponent agent(
            UUID id, UUID modelId, Map<String, Object> options, Map<String, Object> budget) {
        Instant now = Instant.now();
        return new AgentComponent(
                id,
                "protocol-agent",
                "协议专家",
                "",
                modelId,
                "回答充电协议问题",
                options,
                budget,
                null,
                0,
                true,
                0,
                "tester",
                now,
                now);
    }
}
