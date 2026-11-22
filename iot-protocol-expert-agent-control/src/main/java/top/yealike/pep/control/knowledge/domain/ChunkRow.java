package top.yealike.pep.control.knowledge.domain;

import java.util.UUID;

/** 知识检索命中的分块行。 */
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
