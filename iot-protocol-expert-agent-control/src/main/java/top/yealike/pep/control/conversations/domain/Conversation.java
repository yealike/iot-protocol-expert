package top.yealike.pep.control.conversations.domain;

import java.time.Instant;
import java.util.UUID;

/** 会话、消息与引用。字段与 ai_conversation / ai_message 表对应。 */
public record Conversation(
        UUID id,
        String userId,
        String orgId,
        String title,
        String agentKey,
        String agentVersion,
        UUID agentReleaseId,
        String manifestDigest,
        String status,
        long eventCursor,
        Instant createdAt,
        Instant updatedAt,
        Instant lastMessageAt) {}
