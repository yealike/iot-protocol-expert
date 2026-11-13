package top.yealike.ipe.ai.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import top.yealike.ipe.ai.agent.AgentCatalog.Definition;
import top.yealike.ipe.ai.config.AiProperties;
import top.yealike.ipe.ai.gateway.OpsClient;
import top.yealike.ipe.ai.knowledge.KnowledgeService;
import top.yealike.ipe.ai.policy.PolicyService;
import top.yealike.ipe.ai.support.AiExceptions;
import top.yealike.ipe.ai.tool.DelegationTools;
import top.yealike.ipe.ai.tool.OrderTools;
import top.yealike.ipe.ai.tool.ProtocolTools;
import top.yealike.ipe.ai.tool.ToolGuard;
import top.yealike.ipe.ai.tool.ToolNames;
import top.yealike.ipe.ai.tool.ToolSession;

/**
 * Agent 构建与执行。
 *
 * <p>每个 Run 独立构建 Toolkit 与 Agent 实例；工具白名单 = Agent 定义 ∩ 用户策略，未列入的工具直接从 Toolkit 移除。
 * 不启用 meta tool / task list / 文件系统 / shell，避免框架默认能力越过本项目只读边界。
 */
@Service
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    private final AgentCatalog catalog;
    private final AiProperties properties;
    private final ToolGuard guard;
    private final PolicyService policy;
    private final KnowledgeService knowledge;
    private final OpsClient ops;

    public AgentRunner(
            AgentCatalog catalog,
            AiProperties properties,
            ToolGuard guard,
            PolicyService policy,
            KnowledgeService knowledge,
            OpsClient ops) {
        this.catalog = catalog;
        this.properties = properties;
        this.guard = guard;
        this.policy = policy;
        this.knowledge = knowledge;
        this.ops = ops;
    }

    public Definition requireDefinition(String agentKey) {
        Definition definition = catalog.find(agentKey);
        if (definition == null) {
            throw AiExceptions.notFound("Agent 未发布或不存在：" + agentKey);
        }
        return definition;
    }

    public Flux<AgentEvent> stream(Definition definition, List<Msg> messages, ToolSession session) {
        ensureModelAvailable();
        ReActAgent agent = build(definition, session);
        RuntimeContext context = RuntimeContext.builder()
                .userId(session.identity().actor())
                .sessionId(session.conversationId().toString())
                .put("runId", session.runId().toString())
                .put("agentKey", definition.agentKey())
                .build();
        return agent.streamEvents(messages, context)
                .timeout(Duration.ofMillis(properties.model().timeoutMs()))
                .doFinally(signal -> agent.close());
    }

    /** 子 Agent 同步执行：只取最终文本，不向用户暴露其内部步骤。 */
    public String delegate(String agentKey, String question, ToolSession session) {
        ensureModelAvailable();
        Definition definition = requireDefinition(agentKey);
        if (!policy.canUseAgent(session.identity(), agentKey).allowed()) {
            throw AiExceptions.forbidden("AGENT_DENIED", "无权使用该成员");
        }
        ToolSession subSession = session.forAgent(agentKey);
        ReActAgent agent = build(definition, subSession);
        RuntimeContext context = RuntimeContext.builder()
                .userId(subSession.identity().actor())
                .sessionId(subSession.conversationId() + ":" + agentKey)
                .put("runId", session.runId().toString())
                .put("agentKey", agentKey)
                .build();
        try {
            Msg result = agent.call(List.of(new UserMessage(question)), context)
                    .block(Duration.ofMillis(properties.run().timeoutMs()));
            if (result == null) {
                return "成员未返回结果";
            }
            String text = result.getTextContent();
            return text == null || text.isBlank() ? "成员未返回可展示的结论" : text;
        } catch (RuntimeException exception) {
            log.warn("sub agent failed: agent={} run={}", agentKey, session.runId(), exception);
            throw new IllegalStateException("成员执行超时或失败", exception);
        } finally {
            agent.close();
        }
    }

    private void ensureModelAvailable() {
        if (!properties.model().usable()) {
            throw AiExceptions.unavailable("模型未配置，无法执行对话；可通过确定性工具接口查询业务事实");
        }
    }

    private ReActAgent build(Definition definition, ToolSession session) {
        Toolkit toolkit = buildToolkit(definition, session);
        Model model = OpenAIChatModel.builder()
                .baseUrl(properties.model().baseUrl())
                .apiKey(
                        properties.model().apiKey() == null
                                ? ""
                                : properties.model().apiKey())
                .modelName(properties.model().name())
                .generateOptions(io.agentscope.core.model.GenerateOptions.builder()
                        .maxTokens(properties.model().maxTokens())
                        .temperature(properties.model().temperature())
                        .build())
                .build();
        return ReActAgent.builder()
                .name(definition.name())
                .description(definition.description())
                .sysPrompt(definition.sysPrompt())
                .model(model)
                .toolkit(toolkit)
                .maxIters(properties.run().maxIters())
                .enableMetaTool(false)
                .enableTaskList(false)
                .checkRunning(false)
                .build();
    }

    Toolkit buildToolkit(Definition definition, ToolSession session) {
        Toolkit toolkit = new Toolkit();
        toolkit.registration()
                .tool(new OrderTools(session, guard, ops))
                .tool(new ProtocolTools(session, guard, knowledge))
                .tool(new DelegationTools(session, guard, this::delegate))
                .apply();

        List<String> allowed = allowedTools(definition, session);
        List<String> removed = new ArrayList<>();
        for (String tool : ToolNames.ALL) {
            if (!allowed.contains(tool)) {
                toolkit.removeTool(tool);
                removed.add(tool);
            }
        }
        if (!removed.isEmpty()) {
            log.debug("tools disabled for run {}: {}", session.runId(), removed);
        }
        return toolkit;
    }

    public List<String> allowedTools(Definition definition, ToolSession session) {
        List<String> allowed = new ArrayList<>();
        for (String tool : definition.allowedTools()) {
            if (policy.canUseTool(session.identity(), definition.agentKey(), tool)
                    .allowed()) {
                allowed.add(tool);
            }
        }
        return allowed;
    }
}
