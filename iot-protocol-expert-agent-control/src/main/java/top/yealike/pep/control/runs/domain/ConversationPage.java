package top.yealike.pep.control.runs.domain;

import java.util.List;
import top.yealike.pep.control.conversations.domain.Conversation;

/** 会话分页响应，字段沿用前端既有契约。 */
public record ConversationPage(List<Conversation> content, int page, int size, long total, long totalPages) {

    public ConversationPage {
        content = content == null ? List.of() : List.copyOf(content);
    }
}
