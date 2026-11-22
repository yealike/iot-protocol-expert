package top.yealike.pep.control.knowledge.domain;

import java.time.Instant;
import java.util.UUID;

/** 知识文档索引行。 */
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
