package top.yealike.pep.control.knowledge.mapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.control.knowledge.domain.ChunkRow;
import top.yealike.pep.control.knowledge.domain.DocumentRow;

public interface KnowledgeMapper {

    List<ChunkRow> candidates(
            @Param("protocolCode") String protocolCode,
            @Param("protocolVersion") String protocolVersion,
            @Param("keywords") List<String> keywords,
            @Param("limit") int limit);

    List<ChunkRow> candidatesBySourceDocument(
            @Param("sourceDocumentId") String sourceDocumentId,
            @Param("keywords") List<String> keywords,
            @Param("limit") int limit);

    List<DocumentRow> findDocument(@Param("source") String source, @Param("sourceDocumentId") String sourceDocumentId);

    List<DocumentRow> listDocuments();

    int upsertDocument(
            @Param("id") UUID id,
            @Param("source") String source,
            @Param("sourceDocumentId") String sourceDocumentId,
            @Param("protocolCode") String protocolCode,
            @Param("protocolVersion") String protocolVersion,
            @Param("title") String title,
            @Param("contentHash") String contentHash,
            @Param("status") String status,
            @Param("chunkCount") int chunkCount,
            @Param("indexVersion") String indexVersion,
            @Param("failureReason") String failureReason);

    int deleteChunks(@Param("documentId") UUID documentId);

    List<Map<String, Object>> preview(@Param("documentId") UUID documentId);

    int insertChunk(
            @Param("id") UUID id,
            @Param("documentId") UUID documentId,
            @Param("chunkNo") int chunkNo,
            @Param("sectionPath") String sectionPath,
            @Param("pageNo") Integer pageNo,
            @Param("content") String content,
            @Param("normalized") String normalized,
            @Param("keywords") String keywords,
            @Param("tokenCount") int tokenCount,
            @Param("indexVersion") String indexVersion);

    int publishDocument(@Param("documentId") UUID documentId);

    int archiveDocument(@Param("documentId") UUID documentId);

    int recordIndexVersion(
            @Param("id") String id,
            @Param("description") String description,
            @Param("embeddingModel") String embeddingModel,
            @Param("dimensions") int dimensions,
            @Param("chunkSize") int chunkSize,
            @Param("overlap") int overlap);

    Integer countDocuments();

    Integer countPublishedDocuments();

    Integer countChunks();
}
