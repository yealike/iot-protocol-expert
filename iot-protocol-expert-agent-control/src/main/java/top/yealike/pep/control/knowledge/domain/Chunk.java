package top.yealike.pep.control.knowledge.domain;

public record Chunk(
        int chunkNo,
        String sectionPath,
        Integer pageNo,
        String content,
        String normalized,
        String keywords,
        int tokenCount) {}
