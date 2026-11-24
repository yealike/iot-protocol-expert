package top.yealike.pep.runtime.execution;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import top.yealike.pep.runtime.contract.AgentManifest;
import top.yealike.pep.runtime.contract.McpDescriptor;
import top.yealike.pep.runtime.contract.RunLineage;
import top.yealike.pep.runtime.control.ControlClient;
import top.yealike.pep.runtime.mcp.McpAgentTool;
import top.yealike.pep.runtime.mcp.McpConnectionPool;
import top.yealike.pep.runtime.mcp.McpEndpointResolver;
import top.yealike.pep.runtime.observability.RuntimeObservability;
import top.yealike.pep.runtime.prepare.domain.PreparedRelease;
import top.yealike.pep.runtime.prepare.domain.PreparedTool;
import top.yealike.pep.runtime.skill.SkillToolFactory;

/** 每个 Run 独立构建 Agent 与 Toolkit，避免用户间共享可变记忆或工具状态。 */
@Component
public class AgentRuntimeFactory {

    private final PromptAssembler prompts;
    private final SkillToolFactory skills;
    private final McpConnectionPool pool;
    private final McpEndpointResolver endpoints;
    private final ControlClient control;
    private final MeterRegistry metrics;
    private final RuntimeObservability observability;

    public AgentRuntimeFactory(
            PromptAssembler prompts,
            SkillToolFactory skills,
            McpConnectionPool pool,
            McpEndpointResolver endpoints,
            ControlClient control,
            MeterRegistry metrics,
            RuntimeObservability observability) {
        this.prompts = prompts;
        this.skills = skills;
        this.pool = pool;
        this.endpoints = endpoints;
        this.control = control;
        this.metrics = metrics;
        this.observability = observability;
    }

    public ReActAgent build(
            PreparedRelease release, RunGuard guard, RunEventSink sink, String modelSecret, int maxIterations) {
        return build(release, guard, sink, modelSecret, maxIterations, null, null, null);
    }

    /** 根据子 Run 的冻结范围装配 Toolkit 和调用归属。 */
    public ReActAgent build(
            PreparedRelease release,
            RunGuard guard,
            RunEventSink sink,
            String modelSecret,
            int maxIterations,
            RunLineage lineage,
            String instanceId,
            DelegateTransport transport) {
        Toolkit toolkit = new Toolkit();
        for (PreparedTool tool : release.tools()) {
            if (lineage != null
                    && (!tool.descriptor().readOnly()
                            || (!lineage.allowedTools().isEmpty()
                                    && !lineage.allowedTools().contains(tool.alias())))) {
                continue;
            }
            McpDescriptor descriptor = release.mcps().get(tool.mcpKey());
            toolkit.registration()
                    .agentTool(
                            new McpAgentTool(tool, descriptor, guard, control, pool, endpoints, metrics, observability))
                    .apply();
        }
        for (var skillTool : skills.tools(release, sink, guard)) {
            toolkit.registration().agentTool(skillTool).apply();
        }
        AgentManifest manifest = release.manifest();
        if (transport != null && !manifest.delegates().isEmpty()) {
            toolkit.registration()
                    .agentTool(new DelegateAgentTool(release, guard, instanceId, transport))
                    .apply();
        }
        String displayName =
                manifest.displayName() == null || manifest.displayName().isBlank()
                        ? manifest.agentKey()
                        : manifest.displayName();
        return ReActAgent.builder()
                .name(displayName)
                .description(displayName)
                .sysPrompt(prompts.assemble(release))
                .model(model(manifest.model(), modelSecret))
                .toolkit(toolkit)
                .maxIters(maxIterations)
                .enableMetaTool(false)
                .enableTaskList(false)
                .checkRunning(false)
                .middleware(new ModelCallHeadersMiddleware(guard.runId(), lineage))
                .build();
    }

    private Model model(AgentManifest.ModelSpec spec, String secret) {
        int maxTokens = spec.options() == null || spec.options().maxTokens() == null
                ? 2048
                : spec.options().maxTokens();
        double temperature = spec.options() == null || spec.options().temperature() == null
                ? 0.1
                : spec.options().temperature();
        return OpenAIChatModel.builder()
                .baseUrl(spec.baseUrl())
                .apiKey(secret == null ? "" : secret)
                .modelName(spec.modelName())
                .generateOptions(GenerateOptions.builder()
                        .maxTokens(maxTokens)
                        .temperature(temperature)
                        .build())
                .build();
    }
}
