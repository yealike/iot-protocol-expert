package top.yealike.ipe.ai.knowledge;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 知识索引表访问层。显式列清单：pgvector 未安装时表上没有 embedding 列，不能使用 select *。 */
@Repository
public class KnowledgeRepository {

    private final JdbcTemplate jdbc;

    public KnowledgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        return jdbc
                .query(
                        "select id, source, source_document_id, protocol_code, protocol_version, title, content_hash, "
                                + "status, chunk_count, index_version, updated_at from ai_document "
                                + "where source = ? and source_document_id = ?",
                        DOCUMENT_MAPPER,
                        source,
                        sourceDocumentId)
                .stream()
                .findFirst();
    }

    public List<DocumentRow> listDocuments() {
        return jdbc.query(
                "select id, source, source_document_id, protocol_code, protocol_version, title, content_hash, "
                        + "status, chunk_count, index_version, updated_at from ai_document order by updated_at desc",
                DOCUMENT_MAPPER);
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
        jdbc.update(
                """
                insert into ai_document (id, source, source_document_id, protocol_code, protocol_version, title,
                                         content_hash, status, chunk_count, index_version, failure_reason)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (source, source_document_id) do update
                set protocol_code = excluded.protocol_code,
                    protocol_version = excluded.protocol_version,
                    title = excluded.title,
                    content_hash = excluded.content_hash,
                    status = excluded.status,
                    chunk_count = excluded.chunk_count,
                    index_version = excluded.index_version,
                    failure_reason = excluded.failure_reason,
                    updated_at = now()
                """,
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
        jdbc.update("delete from ai_chunk where document_id = ?", documentId);
    }

    public List<Map<String, Object>> preview(UUID documentId) {
        return jdbc.queryForList("select chunk_no as \"chunkNo\", section_path as \"sectionPath\", "
                + "page_no as \"pageNo\", content from ai_chunk where document_id = ? order by chunk_no limit 50", documentId);
    }

    public void insertChunks(UUID documentId, String indexVersion, List<TextChunker.Chunk> chunks) {
        for (TextChunker.Chunk chunk : chunks) {
            jdbc.update(
                    """
                    insert into ai_chunk (id, document_id, chunk_no, section_path, page_no, content, normalized,
                                          keywords, token_count, index_version)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
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
        StringBuilder sql = new StringBuilder("""
                select c.id, c.document_id, c.chunk_no, c.section_path, c.page_no, c.content, c.normalized,
                       c.keywords, d.title, d.protocol_code, d.protocol_version, d.source_document_id
                from ai_chunk c join ai_document d on d.id = c.document_id
                where d.status = 'PUBLISHED'
                """);
        List<Object> args = new java.util.ArrayList<>();
        if (protocolCode != null && !protocolCode.isBlank()) {
            sql.append(" and d.protocol_code = ?");
            args.add(protocolCode);
        }
        if (protocolVersion != null && !protocolVersion.isBlank()) {
            sql.append(" and d.protocol_version = ?");
            args.add(protocolVersion);
        }
        sql.append(" and (");
        for (int i = 0; i < keywords.size(); i++) {
            if (i > 0) {
                sql.append(" or ");
            }
            sql.append("c.normalized like ?");
            args.add("%" + keywords.get(i) + "%");
        }
        sql.append(") limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), CHUNK_MAPPER, args.toArray());
    }

    public int publishDocument(UUID documentId) {
        return jdbc.update(
                "update ai_document set status = 'PUBLISHED', published_at = now(), updated_at = now() "
                        + "where id = ? and status in ('READY_FOR_REVIEW', 'PUBLISHED')",
                documentId);
    }

    public int archiveDocument(UUID documentId) {
        return jdbc.update("update ai_document set status = 'ARCHIVED', updated_at = now() where id = ?", documentId);
    }

    public void recordIndexVersion(
            String id, String description, String embeddingModel, int dimensions, int chunkSize, int overlap) {
        jdbc.update("""
                insert into ai_index_version (id, description, embedding_model, dimensions, chunk_size, chunk_overlap,
                                              status, published_at)
                values (?, ?, ?, ?, ?, ?, 'PUBLISHED', now())
                on conflict (id) do nothing
                """, id, description, embeddingModel, dimensions, chunkSize, overlap);
    }

    public Map<String, Object> stats() {
        Integer documents = jdbc.queryForObject("select count(*) from ai_document", Integer.class);
        Integer published =
                jdbc.queryForObject("select count(*) from ai_document where status = 'PUBLISHED'", Integer.class);
        Integer chunks = jdbc.queryForObject("select count(*) from ai_chunk", Integer.class);
        return Map.of(
                "documents", documents == null ? 0 : documents,
                "published", published == null ? 0 : published,
                "chunks", chunks == null ? 0 : chunks);
    }

    private static final RowMapper<DocumentRow> DOCUMENT_MAPPER = (rs, rowNum) -> new DocumentRow(
            rs.getObject("id", UUID.class),
            rs.getString("source"),
            rs.getString("source_document_id"),
            rs.getString("protocol_code"),
            rs.getString("protocol_version"),
            rs.getString("title"),
            rs.getString("content_hash"),
            rs.getString("status"),
            rs.getInt("chunk_count"),
            rs.getString("index_version"),
            instant(rs.getTimestamp("updated_at")));

    private static final RowMapper<ChunkRow> CHUNK_MAPPER = (rs, rowNum) -> new ChunkRow(
            rs.getObject("id", UUID.class),
            rs.getObject("document_id", UUID.class),
            rs.getInt("chunk_no"),
            rs.getString("section_path"),
            rs.getObject("page_no") == null ? null : rs.getInt("page_no"),
            rs.getString("content"),
            rs.getString("normalized"),
            rs.getString("keywords"),
            rs.getString("title"),
            rs.getString("protocol_code"),
            rs.getString("protocol_version"),
            rs.getString("source_document_id"));

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
