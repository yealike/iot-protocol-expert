package top.yealike.pep.control.api.internal;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.control.api.internal.dto.Context;
import top.yealike.pep.control.integration.OpsIdentityClient;
import top.yealike.pep.control.knowledge.KnowledgeService;
import top.yealike.pep.control.knowledge.domain.Hit;
import top.yealike.pep.control.support.AiExceptions;

/** Higress REST-to-MCP 使用的只读协议知识检索入口。 */
@RestController
@RequestMapping(path = "/internal/ai/knowledge", consumes = "application/json", produces = "application/json")
public class InternalKnowledgeController {

    private static final String OPERATION = "search_protocol_knowledge";

    private final KnowledgeService knowledge;
    private final ControlAiContextCodec contexts;
    private final OpsIdentityClient identities;

    public InternalKnowledgeController(
            KnowledgeService knowledge, ControlAiContextCodec contexts, OpsIdentityClient identities) {
        this.knowledge = knowledge;
        this.contexts = contexts;
        this.identities = identities;
    }

    @PostMapping("/search_protocol_knowledge")
    public Map<String, Object> search(@RequestBody Map<String, Object> args) {
        Context context = context(args);
        // OpsIdentityClient.resolveActor 会再次校验账号可用性与 ai:use 权限。
        identities.resolveActor(context.actor());

        String documentId = requiredText(args, "protocol_document_id", 64);
        String query = requiredText(args, "query", 512);
        int topK = optionalTopK(args.get("top_k"));
        List<Hit> hits = knowledge.searchBySourceDocument(documentId, query, topK);
        if (hits.isEmpty()) {
            throw new AiExceptions.AiException(404, "EVIDENCE_NOT_FOUND", "该协议文档中没有找到与查询匹配的证据");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("protocol_document_id", documentId);
        data.put("query", query);
        data.put("top_k", topK);
        data.put("hits", hits.stream().map(InternalKnowledgeController::hitView).toList());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("data", data);
        envelope.put("data_time", Instant.now().toString());
        envelope.put("source", "CONTROL");
        envelope.put("partial", false);
        envelope.put("warnings", List.of());
        return envelope;
    }

    private Context context(Map<String, Object> args) {
        try {
            Context context = contexts.decode(OPERATION, args.get("_ipe_context"));
            if (context.actor() == null || context.actor().isBlank()) {
                throw new IllegalArgumentException("Missing actor");
            }
            return context;
        } catch (GeneralSecurityException | IllegalArgumentException | tools.jackson.core.JacksonException exception) {
            throw AiExceptions.unauthorized("工具调用上下文无效或已过期");
        }
    }

    private static Map<String, Object> hitView(Hit hit) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("title", hit.title());
        item.put("protocol", hit.protocolCode());
        item.put("version", hit.protocolVersion());
        item.put("section_path", hit.sectionPath());
        item.put("page_no", hit.pageNo());
        item.put("snippet", hit.snippet());
        item.put("content", hit.content());
        item.put("score", hit.score());
        item.put("knowledge_document_id", hit.documentId());
        item.put("protocol_document_id", hit.sourceDocumentId());
        return item;
    }

    private static int optionalTopK(Object raw) {
        if (raw == null) {
            return 3;
        }
        try {
            int value = raw instanceof Number number
                    ? number.intValue()
                    : raw instanceof String text && text.matches("\\d{1,2}") ? Integer.parseInt(text) : -1;
            if (value >= 1 && value <= 8) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // 统一返回参数错误。
        }
        throw AiExceptions.badRequest("top_k 必须在 1 到 8 之间");
    }

    private static String requiredText(Map<String, Object> args, String key, int maxLength) {
        Object raw = args.get(key);
        if (raw == null) {
            throw AiExceptions.badRequest(key + " 不能为空");
        }
        String value = String.valueOf(raw).strip();
        if (value.isEmpty() || value.length() > maxLength) {
            throw AiExceptions.badRequest(key + " 参数格式非法");
        }
        return value;
    }
}
