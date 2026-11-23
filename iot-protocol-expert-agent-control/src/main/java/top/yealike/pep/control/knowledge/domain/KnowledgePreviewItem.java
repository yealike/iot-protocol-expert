package top.yealike.pep.control.knowledge.domain;

/** 知识文档片段预览。 */
public record KnowledgePreviewItem(int chunkNo, String sectionPath, Integer pageNo, String content) {}
