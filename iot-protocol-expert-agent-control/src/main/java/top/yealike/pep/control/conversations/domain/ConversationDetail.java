package top.yealike.pep.control.conversations.domain;

import java.util.List;

public record ConversationDetail(Conversation conversation, List<Message> messages) {}
