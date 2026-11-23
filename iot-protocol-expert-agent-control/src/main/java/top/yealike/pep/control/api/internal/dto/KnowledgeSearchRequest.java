package top.yealike.pep.control.api.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Higress REST-to-MCP 的协议知识检索请求。 */
public record KnowledgeSearchRequest(
        @JsonProperty("_ipe_context") String encodedContext,
        @JsonProperty("protocol_document_id") String protocolDocumentId,
        String query,
        @JsonProperty("top_k") Integer topK) {}
