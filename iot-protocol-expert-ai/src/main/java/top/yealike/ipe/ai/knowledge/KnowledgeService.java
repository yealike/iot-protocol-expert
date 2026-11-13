package top.yealike.ipe.ai.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.yealike.ipe.ai.config.AiProperties;
import top.yealike.ipe.ai.gateway.OpsClient;
import top.yealike.ipe.ai.identity.UserIdentity;
import top.yealike.ipe.ai.support.AiExceptions;

/**
 * 协议知识索引与检索。
 *
 * <p>入库流程：ops 文档 → 文本抽取 → 分块 → 关键字索引 → 待审核 / 发布。 检索先按协议与发布状态过滤，再做关键字精确匹配与加权排序；证据不足时返回空，由调用方明确拒答。
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    private static final String SOURCE = "OPS_PROTOCOL_DOC";
    private static final String INDEX_VERSION = "v1-keyword";

    private final KnowledgeRepository repository;
    private final DocumentTextExtractor extractor;
    private final OpsClient ops;
    private final AiProperties properties;
    private final org.springframework.transaction.support.TransactionTemplate transaction;

    public KnowledgeService(
            KnowledgeRepository repository,
            DocumentTextExtractor extractor,
            OpsClient ops,
            AiProperties properties,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.extractor = extractor;
        this.ops = ops;
        this.properties = properties;
        this.transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    public record IngestResult(
            String documentId, String title, String status, int chunks, String reason, boolean skipped) {}

    public record IngestReport(int total, int indexed, int skipped, int failed, List<IngestResult> items) {}

    public record Hit(
            String documentId,
            String sourceDocumentId,
            String title,
            String protocolCode,
            String protocolVersion,
            String sectionPath,
            Integer pageNo,
            String content,
            String snippet,
            int score) {}

    public IngestReport ingestAll(UserIdentity identity, boolean publish) {
        List<Map<String, Object>> documents = ops.documents(identity);
        List<IngestResult> items = new ArrayList<>();
        int indexed = 0;
        int skipped = 0;
        int failed = 0;
        for (Map<String, Object> document : documents) {
            String sourceId = String.valueOf(document.get("id"));
            String title = String.valueOf(document.getOrDefault("title", sourceId));
            try {
                IngestResult result = transaction.execute(status -> ingestOne(identity, document, publish));
                if (result == null) {
                    throw new IllegalStateException("索引事务未返回结果");
                }
                items.add(result);
                if ("FAILED".equals(result.status())) {
                    failed++;
                } else if (result.skipped()) {
                    skipped++;
                } else {
                    indexed++;
                }
            } catch (RuntimeException exception) {
                failed++;
                log.warn("protocol document ingestion failed: id={}", sourceId, exception);
                var old = repository.findDocument(SOURCE, sourceId);
                if (old.isEmpty() || !"PUBLISHED".equals(old.get().status())) repository.upsertDocument(
                        UUID.randomUUID(),
                        SOURCE,
                        sourceId,
                        str(document.get("protocolCode")),
                        str(document.get("protocolVersion")),
                        title,
                        "",
                        "FAILED",
                        0,
                        INDEX_VERSION,
                        truncate(exception.getMessage()));
                items.add(new IngestResult(sourceId, title, "FAILED", 0, exception.getMessage(), false));
            }
        }
        return new IngestReport(documents.size(), indexed, skipped, failed, items);
    }

    /** 单个文档的索引在一个事务内完成：失败时不会留下半套分块。 */
    protected IngestResult ingestOne(UserIdentity identity, Map<String, Object> document, boolean publish) {
        String sourceId = String.valueOf(document.get("id"));
        String title = String.valueOf(document.getOrDefault("title", sourceId));
        String contentType = str(document.get("contentType"));
        String filename = str(document.get("originalFilename"));
        byte[] content = ops.documentContent(identity, sourceId);
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("协议文档内容为空");
        }
        if (content.length > properties.knowledge().maxDocumentBytes()) {
            throw new IllegalArgumentException("协议文档超过索引大小上限");
        }
        String hash = sha256(content);
        var existing = repository.findDocument(SOURCE, sourceId);
        if (existing.isPresent()
                && hash.equals(existing.get().contentHash())
                && ("PUBLISHED".equals(existing.get().status())
                        || "READY_FOR_REVIEW".equals(existing.get().status()))) {
            if (publish && !"PUBLISHED".equals(existing.get().status())) {
                repository.publishDocument(existing.get().id());
                return new IngestResult(
                        sourceId, title, "PUBLISHED", existing.get().chunkCount(), null, false);
            }
            return new IngestResult(
                    sourceId, title, existing.get().status(), existing.get().chunkCount(), null, true);
        }

        // 当前表结构尚不能同时保存草稿和已发布的两套片段；拒绝覆盖已发布版本。
        if (existing.isPresent() && "PUBLISHED".equals(existing.get().status())) {
            throw new IllegalStateException("已发布文档发生变化；当前索引不支持版本并存，请先下线旧索引再重建");
        }

        UUID documentId = existing.map(KnowledgeRepository.DocumentRow::id).orElseGet(UUID::randomUUID);
        repository.upsertDocument(
                documentId,
                SOURCE,
                sourceId,
                str(document.get("protocolCode")),
                str(document.get("protocolVersion")),
                title,
                hash,
                "INDEXING",
                0,
                INDEX_VERSION,
                null);

        List<TextChunker.Page> pages = extractor.extract(content, contentType, filename);
        List<TextChunker.Chunk> chunks = TextChunker.split(
                pages,
                properties.knowledge().chunkSize(),
                properties.knowledge().chunkOverlap());
        if (chunks.isEmpty()) {
            repository.upsertDocument(
                    documentId,
                    SOURCE,
                    sourceId,
                    str(document.get("protocolCode")),
                    str(document.get("protocolVersion")),
                    title,
                    hash,
                    "FAILED",
                    0,
                    INDEX_VERSION,
                    "未抽取到可用文本，可能需要 OCR 或人工校对");
            return new IngestResult(sourceId, title, "FAILED", 0, "未抽取到可用文本", false);
        }
        repository.deleteChunks(documentId);
        repository.insertChunks(documentId, INDEX_VERSION, chunks);
        String status = publish ? "PUBLISHED" : "READY_FOR_REVIEW";
        repository.upsertDocument(
                documentId,
                SOURCE,
                sourceId,
                str(document.get("protocolCode")),
                str(document.get("protocolVersion")),
                title,
                hash,
                status,
                chunks.size(),
                INDEX_VERSION,
                null);
        if (publish) {
            repository.publishDocument(documentId);
        }
        repository.recordIndexVersion(
                INDEX_VERSION,
                "关键字检索基线，无 embedding",
                null,
                properties.embedding().dimensions(),
                properties.knowledge().chunkSize(),
                properties.knowledge().chunkOverlap());
        return new IngestResult(sourceId, title, status, chunks.size(), null, false);
    }

    public List<Hit> search(String protocolCode, String protocolVersion, String query, Integer topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int limit = Math.min(Math.max(topK == null ? properties.knowledge().topK() : topK, 1), 12);
        List<String> keywords = TextChunker.keywords(query).stream().limit(10).toList();
        if (keywords.isEmpty()) {
            return List.of();
        }
        List<KnowledgeRepository.ChunkRow> candidates =
                repository.candidates(protocolCode, protocolVersion, keywords, limit * 12);
        List<Hit> hits = new ArrayList<>();
        for (KnowledgeRepository.ChunkRow row : candidates) {
            int score = score(row.normalized(), keywords);
            if (score < properties.knowledge().minScore()) {
                continue;
            }
            hits.add(new Hit(
                    row.documentId().toString(),
                    row.sourceDocumentId(),
                    row.title(),
                    row.protocolCode(),
                    row.protocolVersion(),
                    row.sectionPath(),
                    row.pageNo(),
                    row.content(),
                    snippet(row.content()),
                    score));
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed());
        return hits.size() > limit ? List.copyOf(hits.subList(0, limit)) : List.copyOf(hits);
    }

    /** 命令字与英文标识符权重更高，中文二元组用于语义近似。 */
    static int score(String normalized, List<String> keywords) {
        int score = 0;
        for (String keyword : keywords) {
            int weight = weight(keyword);
            int count = count(normalized, keyword);
            score += Math.min(count, 3) * weight;
        }
        return score;
    }

    private static int weight(String keyword) {
        String lower = keyword.toLowerCase(Locale.ROOT);
        if (lower.startsWith("0x")) {
            return 4;
        }
        if (lower.matches("[a-z0-9_.:/-]+")) {
            return 3;
        }
        return 1;
    }

    private static int count(String text, String keyword) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(keyword, index)) >= 0) {
            count++;
            index += keyword.length();
            if (count >= 3) {
                break;
            }
        }
        return count;
    }

    private static String snippet(String content) {
        String flat = content.replaceAll("\\s+", " ").strip();
        return flat.length() <= 400 ? flat : flat.substring(0, 400) + "…";
    }

    public List<KnowledgeRepository.DocumentRow> documents() {
        return repository.listDocuments();
    }

    public List<Map<String, Object>> preview(UUID documentId) {
        return repository.preview(documentId);
    }

    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>(repository.stats());
        stats.put("indexVersion", INDEX_VERSION);
        stats.put("pgvector", "OPTIONAL");
        return stats;
    }

    public void publish(UUID documentId) {
        if (repository.publishDocument(documentId) == 0) {
            throw AiExceptions.notFound("索引不存在或状态不允许发布");
        }
    }

    public void archive(UUID documentId) {
        if (repository.archiveDocument(documentId) == 0) {
            throw AiExceptions.notFound("索引不存在");
        }
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException("sha256 unavailable", exception);
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 480 ? value : value.substring(0, 480);
    }

    public Instant now() {
        return Instant.now();
    }

    /** 供审计与前端展示：不返回原文，只返回摘要。 */
    public static String describe(Hit hit) {
        return hit.title() + (hit.sectionPath() == null ? "" : " / " + hit.sectionPath());
    }

    static String utf8(byte[] content) {
        return new String(content, StandardCharsets.UTF_8);
    }
}
