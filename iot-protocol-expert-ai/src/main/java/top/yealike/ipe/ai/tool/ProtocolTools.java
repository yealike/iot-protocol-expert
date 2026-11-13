package top.yealike.ipe.ai.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import top.yealike.ipe.ai.conversation.ConversationRecords.Citation;
import top.yealike.ipe.ai.knowledge.KnowledgeService;
import top.yealike.ipe.ai.support.JsonSupport;

/** 协议知识检索工具：只返回已发布索引片段，明确携带文档、版本、章节与页码。 */
public class ProtocolTools {

    private final ToolSession session;
    private final ToolGuard guard;
    private final KnowledgeService knowledge;

    public ProtocolTools(ToolSession session, ToolGuard guard, KnowledgeService knowledge) {
        this.session = session;
        this.guard = guard;
        this.knowledge = knowledge;
    }

    @Tool(
            name = ToolNames.SEARCH_PROTOCOL_KNOWLEDGE,
            description = "按协议编码/版本与查询关键词检索已发布的协议文档片段，返回原文片段、章节与页码",
            readOnly = true)
    public String searchProtocolKnowledge(
            @ToolParam(name = "query", required = true, description = "查询内容，例如「0x3B 停止原因定义」") String query,
            @ToolParam(name = "protocol_code", required = false, description = "协议编码，例如 YKC16 / OCPP16J")
                    String protocolCode,
            @ToolParam(name = "protocol_version", required = false, description = "协议版本，例如 1.6 / 1.6j")
                    String protocolVersion,
            @ToolParam(name = "top_k", required = false, description = "返回片段数量，默认取系统配置") Integer topK) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", query);
        args.put("protocol_code", protocolCode);
        args.put("protocol_version", protocolVersion);
        ToolGuard.Denied denied = guard.check(session, ToolNames.SEARCH_PROTOCOL_KNOWLEDGE);
        if (denied != null) {
            guard.recordDenied(session, ToolNames.SEARCH_PROTOCOL_KNOWLEDGE, args, denied.reason());
            return JsonSupport.write(error(denied.reason()));
        }
        UUID callId = guard.start(session, ToolNames.SEARCH_PROTOCOL_KNOWLEDGE, args);
        long started = System.nanoTime();
        try {
            List<KnowledgeService.Hit> hits = knowledge.search(protocolCode, protocolVersion, query, topK);
            List<Map<String, Object>> items = new ArrayList<>();
            for (KnowledgeService.Hit hit : hits) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("document_id", hit.documentId());
                item.put("title", hit.title());
                item.put("protocol_code", hit.protocolCode());
                item.put("protocol_version", hit.protocolVersion());
                item.put("section_path", hit.sectionPath());
                item.put("page_no", hit.pageNo());
                item.put("content", hit.content());
                item.put("score", hit.score());
                items.add(item);
                session.addCitation(new Citation(
                        hit.documentId(), hit.title(), hit.protocolVersion(), hit.sectionPath(), hit.pageNo(), hit.sourceDocumentId()));
            }
            List<String> warnings = new ArrayList<>();
            if (items.isEmpty()) {
                warnings.add("未检索到匹配的协议片段；如确需答案请补充协议编码、版本或更精确的命令字");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("hits", items);
            guard.finish(callId, "SUCCEEDED", ToolGuard.summarize(data, items.isEmpty(), warnings), started);
            return JsonSupport.write(envelope(data, "KNOWLEDGE", items.isEmpty(), warnings));
        } catch (RuntimeException exception) {
            guard.finish(callId, "FAILED", Map.of("error", "KNOWLEDGE_UNAVAILABLE"), started);
            return JsonSupport.write(error("协议知识检索失败，请稍后重试"));
        }
    }

    private Map<String, Object> envelope(Object data, String source, boolean partial, List<String> warnings) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("data", data);
        value.put("data_time", Instant.now().toString());
        value.put("source", source);
        value.put("partial", partial);
        value.put("warnings", warnings);
        return value;
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("data", Map.of());
        value.put("data_time", Instant.now().toString());
        value.put("source", "KNOWLEDGE");
        value.put("partial", true);
        value.put("warnings", List.of(message));
        value.put("error", message);
        return value;
    }
}
