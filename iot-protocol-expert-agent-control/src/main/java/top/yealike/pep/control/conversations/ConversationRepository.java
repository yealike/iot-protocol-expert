package top.yealike.pep.control.conversations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import top.yealike.pep.control.conversations.ConversationRecords.Conversation;
import top.yealike.pep.control.conversations.mapper.ConversationMapper;
import top.yealike.pep.control.support.JsonSupport;

/** 会话与消息持久化。所有递增序号用 UPDATE ... RETURNING 原子分配，避免并发重号。 */
@Repository
public class ConversationRepository {

    private final ConversationMapper mapper;

    public ConversationRepository(ConversationMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(Conversation conversation) {
        mapper.insert(
                conversation.id(),
                conversation.userId(),
                conversation.orgId(),
                conversation.title(),
                conversation.agentKey(),
                conversation.agentVersion(),
                conversation.agentReleaseId(),
                conversation.manifestDigest(),
                conversation.status(),
                conversation.createdAt(),
                conversation.updatedAt());
    }

    public Optional<Conversation> find(UUID id) {
        List<Conversation> rows = mapper.find(id);
        return rows.stream().findFirst();
    }

    public List<Conversation> listByUser(String userId, int limit, int offset) {
        return mapper.listByUser(userId, limit, offset);
    }

    public long countByUser(String userId) {
        Long count = mapper.countByUser(userId);
        return count == null ? 0 : count;
    }

    public void touch(UUID id, Instant now) {
        mapper.touch(now, id);
    }

    /** 原子分配下一条消息序号。 */
    public long nextMessageSeq(UUID conversationId) {
        Long seq = mapper.nextMessageSeq(conversationId);
        if (seq == null) {
            throw new IllegalStateException("conversation not found: " + conversationId);
        }
        return seq;
    }

    public void insertMessage(
            UUID id,
            UUID conversationId,
            UUID runId,
            String role,
            String content,
            List<ConversationRecords.Citation> citations,
            String status,
            long seq,
            Instant createdAt) {
        mapper.insertMessage(
                id,
                conversationId,
                runId,
                role,
                content,
                citations == null ? null : JsonSupport.write(citations),
                status,
                seq,
                createdAt);
    }

    public List<ConversationRecords.Message> listMessages(UUID conversationId, int limit) {
        return mapper.listMessages(conversationId, limit);
    }

    public List<ConversationRecords.Message> listMessagesAfter(UUID conversationId, long afterSeq, int limit) {
        return mapper.listMessagesAfter(conversationId, afterSeq, limit);
    }

    /** 上下文截止于当前提问，避免队列中后续问题提前进入当前 Run。 */
    public List<ConversationRecords.Message> listMessagesThrough(UUID conversationId, UUID messageId, int limit) {
        return mapper.listMessagesThrough(conversationId, messageId, limit);
    }

    public long countMessages(UUID conversationId) {
        Long count = mapper.countMessages(conversationId);
        return count == null ? 0 : count;
    }

    public void updateMessageContent(UUID messageId, String content, String status) {
        mapper.updateMessageContent(content, status, messageId);
    }
}
