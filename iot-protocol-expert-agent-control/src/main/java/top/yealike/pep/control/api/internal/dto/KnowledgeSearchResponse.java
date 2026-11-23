package top.yealike.pep.control.api.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** 工具调用的统一证据信封。 */
public record KnowledgeSearchResponse(
        Data data, @JsonProperty("data_time") String dataTime, String source, boolean partial, List<String> warnings) {

    public KnowledgeSearchResponse {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public record Data(
            @JsonProperty("protocol_document_id") String protocolDocumentId,
            String query,
            @JsonProperty("top_k") int topK,
            List<Hit> hits) {

        public Data {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
    }

    public record Hit(
            String title,
            String protocol,
            String version,
            @JsonProperty("section_path") String sectionPath,
            @JsonProperty("page_no") Integer pageNo,
            String snippet,
            String content,
            int score,
            @JsonProperty("knowledge_document_id") String knowledgeDocumentId,
            @JsonProperty("protocol_document_id") String protocolDocumentId) {}
}
