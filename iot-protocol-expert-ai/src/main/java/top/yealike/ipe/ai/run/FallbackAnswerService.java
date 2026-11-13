package top.yealike.ipe.ai.run;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import top.yealike.ipe.ai.agent.AgentCatalog;
import top.yealike.ipe.ai.gateway.OpsClient;
import top.yealike.ipe.ai.knowledge.KnowledgeService;
import top.yealike.ipe.ai.policy.PolicyService;
import top.yealike.ipe.ai.support.JsonSupport;
import top.yealike.ipe.ai.tool.OrderTools;
import top.yealike.ipe.ai.tool.ProtocolTools;
import top.yealike.ipe.ai.tool.ToolGuard;
import top.yealike.ipe.ai.tool.ToolSession;

/**
 * 模型未配置时的确定性降级。
 *
 * <p>只用可识别的订单号或命令字直接调用只读工具，把真实结果拼成说明；识别不到参数时明确引导，不猜测、不编造。
 */
@Service
public class FallbackAnswerService {

    private static final Pattern ORDER_NO = Pattern.compile("(?<![0-9A-Za-z])([0-9A-Za-z]{8,32})(?![0-9A-Za-z])");
    private static final Pattern HEX = Pattern.compile("0x[0-9A-Fa-f]{1,4}");

    private final ToolGuard guard;
    private final OpsClient ops;
    private final KnowledgeService knowledge;
    private final PolicyService policy;

    public FallbackAnswerService(ToolGuard guard, OpsClient ops, KnowledgeService knowledge, PolicyService policy) {
        this.guard = guard;
        this.ops = ops;
        this.knowledge = knowledge;
        this.policy = policy;
    }

    public String answer(RunRecords.Run run, ToolSession session, AgentCatalog.Definition definition) {
        String question = run.question() == null ? "" : run.question();
        StringBuilder out = new StringBuilder();
        out.append("> 未启用模型（`IPE_MODEL_ENABLED=false`），以下为确定性工具结果，不含模型推断。\n\n");

        String orderNo = extractOrderNo(question);
        String hex = extractHex(question);
        boolean handled = false;

        // 降级路径按成员身份校验工具权限，而不是沿用 Leader 的委派工具集
        ToolSession orderSession = session.forAgent(AgentCatalog.ORDER_SERVICE);
        ToolSession protocolSession = session.forAgent(AgentCatalog.PROTOCOL_EXPERT);
        boolean canOrder = ((AgentCatalog.TEAM_LEADER.equals(definition.agentKey())
                        && policy.canUseTool(session.identity(), AgentCatalog.TEAM_LEADER, "ask_order_agent").allowed())
                        || AgentCatalog.ORDER_SERVICE.equals(definition.agentKey()))
                && policy.canUseTool(orderSession.identity(), AgentCatalog.ORDER_SERVICE, "get_order_detail").allowed();
        boolean canProtocol = ((AgentCatalog.TEAM_LEADER.equals(definition.agentKey())
                        && policy.canUseTool(session.identity(), AgentCatalog.TEAM_LEADER, "ask_protocol_agent").allowed())
                        || AgentCatalog.PROTOCOL_EXPERT.equals(definition.agentKey())) && policy.canUseTool(
                        protocolSession.identity(), AgentCatalog.PROTOCOL_EXPERT, "search_protocol_knowledge")
                .allowed();

        if (orderNo != null && canOrder) {
            handled = true;
            OrderTools tools = new OrderTools(orderSession, guard, ops);
            out.append("### 订单详情 `").append(orderNo).append("`\n\n");
            out.append(codeBlock(tools.getOrderDetail(orderNo))).append('\n');
            if (policy.canUseTool(orderSession.identity(), AgentCatalog.ORDER_SERVICE, "get_order_bills")
                    .allowed()) {
                out.append("### 账单 `").append(orderNo).append("`\n\n");
                out.append(codeBlock(tools.getOrderBills(orderNo))).append('\n');
            }
            if (question.contains("SOC") || question.contains("soc") || question.contains("遥测")) {
                out.append("### 遥测摘要 `").append(orderNo).append("`\n\n");
                out.append(codeBlock(tools.getOrderTelemetrySummary(orderNo, null)))
                        .append('\n');
            }
        }

        if ((hex != null || question.contains("协议") || question.contains("命令字")) && canProtocol) {
            handled = true;
            ProtocolTools tools = new ProtocolTools(protocolSession, guard, knowledge);
            String query = hex == null ? question : hex;
            out.append("### 协议知识检索 `").append(query).append("`\n\n");
            out.append(codeBlock(tools.searchProtocolKnowledge(query, null, null, 5)))
                    .append('\n');
        }

        if (!handled) {
            out.append("未从问题中识别到可查询的订单号或命令字（如 `0x3B`）。\n\n");
            out.append("可直接提供：\n\n");
            out.append("- 订单号：用于查询订单、账单与遥测摘要；\n");
            out.append("- 协议编码与版本 + 命令字：用于检索协议条文。\n\n");
            out.append("如需自然语言分析，请配置 `IPE_MODEL_BASE_URL`、`IPE_MODEL_API_KEY` 等模型参数后重试。\n");
        }
        if (!session.citations().isEmpty()) {
            out.append("\n### 引用\n\n");
            for (var citation : session.citations()) {
                out.append("- ")
                        .append(citation.title())
                        .append(" / ")
                        .append(citation.sectionPath() == null ? "章节不可用" : citation.sectionPath())
                        .append(" / ")
                        .append(citation.pageNo() == null ? "页码不可用" : "第 " + citation.pageNo() + " 页")
                        .append('\n');
            }
        }
        session.addWarning("模型未配置，本次为确定性工具结果");
        return out.toString();
    }

    static String extractOrderNo(String question) {
        Matcher matcher = ORDER_NO.matcher(question);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token.matches(".*\\d.*") && !token.equalsIgnoreCase("protocol")) {
                return token;
            }
        }
        return null;
    }

    static String extractHex(String question) {
        Matcher matcher = HEX.matcher(question);
        return matcher.find() ? matcher.group() : null;
    }

    private static String codeBlock(String json) {
        String pretty;
        try {
            pretty =
                    JsonSupport.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(JsonSupport.readMap(json));
        } catch (RuntimeException exception) {
            pretty = json;
        }
        return "```json\n" + pretty + "\n```";
    }
}
