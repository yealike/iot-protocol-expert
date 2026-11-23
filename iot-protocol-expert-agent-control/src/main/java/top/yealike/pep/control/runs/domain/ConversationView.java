package top.yealike.pep.control.runs.domain;

import java.util.List;
import top.yealike.pep.control.conversations.domain.Conversation;

public record ConversationView(
        Conversation conversation, List<MessageView> messages, RunView currentRun, RunView latestRun) {

    public ConversationView {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
