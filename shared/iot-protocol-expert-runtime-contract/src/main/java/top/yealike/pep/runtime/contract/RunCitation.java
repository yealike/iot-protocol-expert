package top.yealike.pep.runtime.contract;

/** Runtime 回传的答案引用。 */
public record RunCitation(
        String documentId,
        String title,
        String protocolVersion,
        String sectionPath,
        Integer pageNo,
        String sourceDocumentId) {}
