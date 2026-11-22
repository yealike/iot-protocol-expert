package top.yealike.pep.control.runs.domain;

import java.util.List;
import top.yealike.pep.control.conversations.domain.Conversation;
import top.yealike.pep.control.conversations.domain.Message;

public record ConversationView(Conversation conversation, List<Message> messages, Run currentRun, Run latestRun) {}
