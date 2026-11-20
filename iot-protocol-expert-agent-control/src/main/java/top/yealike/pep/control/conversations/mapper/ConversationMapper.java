package top.yealike.pep.control.conversations.mapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.control.conversations.ConversationRecords;
import top.yealike.pep.control.conversations.ConversationRecords.Conversation;

public interface ConversationMapper {

    int insert(
            @Param("id") UUID id,
            @Param("userId") String userId,
            @Param("orgId") String orgId,
            @Param("title") String title,
            @Param("agentKey") String agentKey,
            @Param("agentVersion") String agentVersion,
            @Param("agentReleaseId") UUID agentReleaseId,
            @Param("manifestDigest") String manifestDigest,
            @Param("status") String status,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt);

    List<Conversation> find(@Param("id") UUID id);

    List<Conversation> listByUser(
            @Param("userId") String userId, @Param("limit") int limit, @Param("offset") int offset);

    Long countByUser(@Param("userId") String userId);

    int touch(@Param("now") Instant now, @Param("id") UUID id);

    Long nextMessageSeq(@Param("conversationId") UUID conversationId);

    int insertMessage(
            @Param("id") UUID id,
            @Param("conversationId") UUID conversationId,
            @Param("runId") UUID runId,
            @Param("role") String role,
            @Param("content") String content,
            @Param("citationsJson") String citationsJson,
            @Param("status") String status,
            @Param("seq") long seq,
            @Param("createdAt") Instant createdAt);

    List<ConversationRecords.Message> listMessages(
            @Param("conversationId") UUID conversationId, @Param("limit") int limit);

    List<ConversationRecords.Message> listMessagesAfter(
            @Param("conversationId") UUID conversationId, @Param("afterSeq") long afterSeq, @Param("limit") int limit);

    List<ConversationRecords.Message> listMessagesThrough(
            @Param("conversationId") UUID conversationId,
            @Param("messageId") UUID messageId,
            @Param("limit") int limit);

    Long countMessages(@Param("conversationId") UUID conversationId);

    int updateMessageContent(
            @Param("content") String content, @Param("status") String status, @Param("messageId") UUID messageId);
}
