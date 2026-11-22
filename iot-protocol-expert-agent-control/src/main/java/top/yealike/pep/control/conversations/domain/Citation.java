package top.yealike.pep.control.conversations.domain;

public record Citation(
        String documentId,
        String title,
        String protocolVersion,
        String sectionPath,
        Integer pageNo,
        String sourceDocumentId) {
    public Citation(String documentId, String title, String protocolVersion, String sectionPath, Integer pageNo) {
        this(documentId, title, protocolVersion, sectionPath, pageNo, null);
    }
}
