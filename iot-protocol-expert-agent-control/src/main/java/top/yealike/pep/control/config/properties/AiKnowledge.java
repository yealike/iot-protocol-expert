package top.yealike.pep.control.config.properties;

public record AiKnowledge(int chunkSize, int chunkOverlap, int topK, int minScore, int maxDocumentBytes) {}
