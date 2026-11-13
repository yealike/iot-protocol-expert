package top.yealike.ipe.ai.conversation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 会话、消息与引用。字段与 ai_conversation / ai_message 表对应。 */
public final class ConversationRecords {

    private ConversationRecords() {}

    public record Conversation(
            UUID id,
            String userId,
            String orgId,
            String title,
            String agentKey,
            String agentVersion,
            String status,
            long eventCursor,
            Instant createdAt,
            Instant updatedAt,
            Instant lastMessageAt) {}

    public record Citation(
            String documentId, String title, String protocolVersion, String sectionPath, Integer pageNo, String sourceDocumentId) {
        public Citation(String documentId, String title, String protocolVersion, String sectionPath, Integer pageNo) {
            this(documentId, title, protocolVersion, sectionPath, pageNo, null);
        }
    }

    public record Message(
            UUID id,
            UUID conversationId,
            UUID runId,
            String role,
            String content,
            List<Citation> citations,
            String status,
            long seq,
            Instant createdAt) {}

    public record ConversationView(Conversation conversation, List<Message> messages, Object currentRun) {}
}
