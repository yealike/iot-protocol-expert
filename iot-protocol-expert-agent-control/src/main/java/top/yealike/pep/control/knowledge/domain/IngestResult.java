package top.yealike.pep.control.knowledge.domain;

public record IngestResult(
        String documentId, String title, String status, int chunks, String reason, boolean skipped) {}
