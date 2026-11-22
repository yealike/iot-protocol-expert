package top.yealike.pep.control.knowledge.domain;

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
