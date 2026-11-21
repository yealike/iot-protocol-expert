package top.yealike.pep.control.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import top.yealike.pep.control.knowledge.mapper.KnowledgeMapper;

/** 知识索引表访问层。显式列清单：pgvector 未安装时表上没有 embedding 列，不能使用 select *。 */
@Repository
public class KnowledgeRepository {

    private final KnowledgeMapper mapper;

    public KnowledgeRepository(KnowledgeMapper mapper) {
        this.mapper = mapper;
    }

    public record DocumentRow(
            UUID id,
            String source,
            String sourceDocumentId,
            String protocolCode,
            String protocolVersion,
            String title,
            String contentHash,
            String status,
            int chunkCount,
            String indexVersion,
            Instant updatedAt) {}

    public record ChunkRow(
            UUID id,
            UUID documentId,
            int chunkNo,
            String sectionPath,
            Integer pageNo,
            String content,
            String normalized,
            String keywords,
            String title,
            String protocolCode,
            String protocolVersion,
            String sourceDocumentId) {}

    public Optional<DocumentRow> findDocument(String source, String sourceDocumentId) {
        return mapper.findDocument(source, sourceDocumentId).stream().findFirst();
    }

    public List<DocumentRow> listDocuments() {
        return mapper.listDocuments();
    }

    public void upsertDocument(
            UUID id,
            String source,
            String sourceDocumentId,
            String protocolCode,
            String protocolVersion,
            String title,
            String contentHash,
            String status,
            int chunkCount,
            String indexVersion,
            String failureReason) {
        mapper.upsertDocument(
                id,
                source,
                sourceDocumentId,
                protocolCode,
                protocolVersion,
                title,
                contentHash,
                status,
                chunkCount,
                indexVersion,
                failureReason);
    }

    public void deleteChunks(UUID documentId) {
        mapper.deleteChunks(documentId);
    }

    public List<Map<String, Object>> preview(UUID documentId) {
        return mapper.preview(documentId);
    }

    public void insertChunks(UUID documentId, String indexVersion, List<TextChunker.Chunk> chunks) {
        for (TextChunker.Chunk chunk : chunks) {
            mapper.insertChunk(
                    UUID.randomUUID(),
                    documentId,
                    chunk.chunkNo(),
                    chunk.sectionPath(),
                    chunk.pageNo(),
                    chunk.content(),
                    chunk.normalized(),
                    chunk.keywords(),
                    chunk.tokenCount(),
                    indexVersion);
        }
    }

    /** 关键字候选召回：先在库内用 like 收敛，再在应用层按关键词加权排序。 */
    public List<ChunkRow> candidates(String protocolCode, String protocolVersion, List<String> keywords, int limit) {
        if (keywords.isEmpty()) {
            return List.of();
        }
        return mapper.candidates(protocolCode, protocolVersion, keywords, limit);
    }

    /** 产品绑定文档的精确召回，避免同协议同版本的多份文档互相污染。 */
    public List<ChunkRow> candidatesBySourceDocument(String sourceDocumentId, List<String> keywords, int limit) {
        if (sourceDocumentId == null || sourceDocumentId.isBlank() || keywords.isEmpty()) {
            return List.of();
        }
        return mapper.candidatesBySourceDocument(sourceDocumentId, keywords, limit);
    }

    public int publishDocument(UUID documentId) {
        return mapper.publishDocument(documentId);
    }

    public int archiveDocument(UUID documentId) {
        return mapper.archiveDocument(documentId);
    }

    public void recordIndexVersion(
            String id, String description, String embeddingModel, int dimensions, int chunkSize, int overlap) {
        mapper.recordIndexVersion(id, description, embeddingModel, dimensions, chunkSize, overlap);
    }

    public Map<String, Object> stats() {
        Integer documents = mapper.countDocuments();
        Integer published = mapper.countPublishedDocuments();
        Integer chunks = mapper.countChunks();
        return Map.of(
                "documents", documents == null ? 0 : documents,
                "published", published == null ? 0 : published,
                "chunks", chunks == null ? 0 : chunks);
    }
}
