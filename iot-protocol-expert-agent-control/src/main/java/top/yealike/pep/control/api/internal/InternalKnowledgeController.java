package top.yealike.pep.control.api.internal;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.control.api.internal.dto.Context;
import top.yealike.pep.control.api.internal.dto.KnowledgeSearchRequest;
import top.yealike.pep.control.api.internal.dto.KnowledgeSearchResponse;
import top.yealike.pep.control.integration.OpsIdentityClient;
import top.yealike.pep.control.knowledge.KnowledgeService;
import top.yealike.pep.control.knowledge.domain.Hit;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.observability.api.RawResponse;

/** Higress REST-to-MCP 使用的只读协议知识检索入口。 */
@RawResponse
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
    public KnowledgeSearchResponse search(@RequestBody KnowledgeSearchRequest request) {
        Context context = context(request.encodedContext());
        // OpsIdentityClient.resolveActor 会再次校验账号可用性与 ai:use 权限。
        identities.resolveActor(context.actor());

        String documentId = requiredText(request.protocolDocumentId(), "protocol_document_id", 64);
        String query = requiredText(request.query(), "query", 512);
        int topK = optionalTopK(request.topK());
        List<Hit> hits = knowledge.searchBySourceDocument(documentId, query, topK);
        if (hits.isEmpty()) {
            throw new AiExceptions.AiException(404, "EVIDENCE_NOT_FOUND", "该协议文档中没有找到与查询匹配的证据");
        }

        return new KnowledgeSearchResponse(
                new KnowledgeSearchResponse.Data(
                        documentId,
                        query,
                        topK,
                        hits.stream().map(InternalKnowledgeController::hitView).toList()),
                Instant.now().toString(),
                "CONTROL",
                false,
                List.of());
    }

    private Context context(String encodedContext) {
        try {
            Context context = contexts.decode(OPERATION, encodedContext);
            if (context.actor() == null || context.actor().isBlank()) {
                throw new IllegalArgumentException("Missing actor");
            }
            return context;
        } catch (GeneralSecurityException | IllegalArgumentException | tools.jackson.core.JacksonException exception) {
            throw AiExceptions.unauthorized("工具调用上下文无效或已过期");
        }
    }

    private static KnowledgeSearchResponse.Hit hitView(Hit hit) {
        return new KnowledgeSearchResponse.Hit(
                hit.title(),
                hit.protocolCode(),
                hit.protocolVersion(),
                hit.sectionPath(),
                hit.pageNo(),
                hit.snippet(),
                hit.content(),
                hit.score(),
                hit.documentId(),
                hit.sourceDocumentId());
    }

    private static int optionalTopK(Integer raw) {
        if (raw == null) {
            return 3;
        }
        if (raw >= 1 && raw <= 8) {
            return raw;
        }
        throw AiExceptions.badRequest("top_k 必须在 1 到 8 之间");
    }

    private static String requiredText(String raw, String key, int maxLength) {
        if (raw == null) {
            throw AiExceptions.badRequest(key + " 不能为空");
        }
        String value = raw.strip();
        if (value.isEmpty() || value.length() > maxLength) {
            throw AiExceptions.badRequest(key + " 参数格式非法");
        }
        return value;
    }
}
