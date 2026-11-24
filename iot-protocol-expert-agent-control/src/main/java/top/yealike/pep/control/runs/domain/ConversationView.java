package top.yealike.pep.control.runs.domain;

import java.util.List;
import top.yealike.pep.control.conversations.domain.Conversation;
import top.yealike.pep.control.runs.delegation.DelegationView;

public record ConversationView(
        Conversation conversation,
        List<MessageView> messages,
        RunView currentRun,
        RunView latestRun,
        List<DelegationView> delegations) {

    public ConversationView {
        messages = messages == null ? List.of() : List.copyOf(messages);
        delegations = delegations == null ? List.of() : List.copyOf(delegations);
    }
}
