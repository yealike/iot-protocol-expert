package top.yealike.pep.control.runs.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import top.yealike.pep.control.conversations.domain.Message;
import top.yealike.pep.runtime.contract.RunCitation;
import top.yealike.pep.runtime.contract.RunUsage;

/** 会话消息视图。 */
public record MessageView(
        UUID id,
        UUID conversationId,
        UUID runId,
        String role,
        String content,
        List<RunCitation> citations,
        String status,
        long seq,
        Instant createdAt,
        RunUsage usage) {

    public MessageView {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    public static MessageView from(Message message) {
        return new MessageView(
                message.id(),
                message.conversationId(),
                message.runId(),
                message.role(),
                message.content(),
                message.citations(),
                message.status(),
                message.seq(),
                message.createdAt(),
                message.usage());
    }
}
