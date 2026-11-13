package top.yealike.ipe.ai.conversation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import top.yealike.ipe.ai.conversation.ConversationRecords.Conversation;
import top.yealike.ipe.ai.support.JsonSupport;

/** 会话与消息持久化。所有递增序号用 UPDATE ... RETURNING 原子分配，避免并发重号。 */
@Repository
public class ConversationRepository {

    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Conversation conversation) {
        jdbc.update(
                """
                insert into ai_conversation
                    (id, user_id, org_id, title, agent_key, agent_version, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                conversation.id(),
                conversation.userId(),
                conversation.orgId(),
                conversation.title(),
                conversation.agentKey(),
                conversation.agentVersion(),
                conversation.status(),
                Timestamp.from(conversation.createdAt()),
                Timestamp.from(conversation.updatedAt()));
    }

    public Optional<Conversation> find(UUID id) {
        List<Conversation> rows = jdbc.query("select * from ai_conversation where id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    public List<Conversation> listByUser(String userId, int limit, int offset) {
        return jdbc.query(
                "select * from ai_conversation where user_id = ? order by updated_at desc limit ? offset ?",
                MAPPER,
                userId,
                limit,
                offset);
    }

    public long countByUser(String userId) {
        Long count = jdbc.queryForObject("select count(*) from ai_conversation where user_id = ?", Long.class, userId);
        return count == null ? 0 : count;
    }

    public void touch(UUID id, Instant now) {
        jdbc.update("update ai_conversation set updated_at = ? where id = ?", Timestamp.from(now), id);
    }

    /** 原子分配下一条消息序号。 */
    public long nextMessageSeq(UUID conversationId) {
        Long seq = jdbc.queryForObject(
                "update ai_conversation set message_seq = message_seq + 1, last_message_at = now(), updated_at = now() "
                        + "where id = ? returning message_seq",
                Long.class,
                conversationId);
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
        jdbc.update(
                """
                insert into ai_message (id, conversation_id, run_id, role, content, citations, status, seq, created_at)
                values (?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?)
                """,
                id,
                conversationId,
                runId,
                role,
                content,
                citations == null ? null : JsonSupport.write(citations),
                status,
                seq,
                Timestamp.from(createdAt));
    }

    public List<ConversationRecords.Message> listMessages(UUID conversationId, int limit) {
        return jdbc.query("""
                select * from (
                    select * from ai_message where conversation_id = ? order by seq desc limit ?
                ) t order by seq asc
                """, MESSAGE_MAPPER, conversationId, limit);
    }

    public List<ConversationRecords.Message> listMessagesAfter(UUID conversationId, long afterSeq, int limit) {
        return jdbc.query(
                "select * from ai_message where conversation_id = ? and seq > ? order by seq asc limit ?",
                MESSAGE_MAPPER,
                conversationId,
                afterSeq,
                limit);
    }

    /** 上下文截止于当前提问，避免队列中后续问题提前进入当前 Run。 */
    public List<ConversationRecords.Message> listMessagesThrough(UUID conversationId, UUID messageId, int limit) {
        return jdbc.query("""
                select * from (
                    select * from ai_message
                    where conversation_id = ? and seq <= (select seq from ai_message where id = ? and conversation_id = ?)
                    order by seq desc limit ?
                ) t order by seq asc
                """, MESSAGE_MAPPER, conversationId, messageId, conversationId, limit);
    }

    public long countMessages(UUID conversationId) {
        Long count = jdbc.queryForObject(
                "select count(*) from ai_message where conversation_id = ?", Long.class, conversationId);
        return count == null ? 0 : count;
    }

    public void updateMessageContent(UUID messageId, String content, String status) {
        jdbc.update("update ai_message set content = ?, status = ? where id = ?", content, status, messageId);
    }

    private static final RowMapper<Conversation> MAPPER = (rs, rowNum) -> new Conversation(
            rs.getObject("id", UUID.class),
            rs.getString("user_id"),
            rs.getString("org_id"),
            rs.getString("title"),
            rs.getString("agent_key"),
            rs.getString("agent_version"),
            rs.getString("status"),
            rs.getLong("event_cursor"),
            instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("updated_at")),
            instant(rs.getTimestamp("last_message_at")));

    private static final RowMapper<ConversationRecords.Message> MESSAGE_MAPPER = (rs, rowNum) -> {
        String citations = rs.getString("citations");
        List<ConversationRecords.Citation> parsed =
                citations == null ? List.of() : JsonSupport.readList(citations, ConversationRecords.Citation.class);
        return new ConversationRecords.Message(
                rs.getObject("id", UUID.class),
                rs.getObject("conversation_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getString("role"),
                rs.getString("content"),
                parsed,
                rs.getString("status"),
                rs.getLong("seq"),
                instant(rs.getTimestamp("created_at")));
    };

    private static java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
