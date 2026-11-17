package top.yealike.pep.runtime.execution;

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.UserMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import top.yealike.pep.runtime.contract.RunContext;
import top.yealike.pep.runtime.contract.RunMessage;

/** Control 的受控消息历史转成 AgentScope 消息；不接受 Runtime 自行拼接身份信息。 */
@Component
public class RunMessageMapper {

    private static final int MAX_MESSAGES = 120;
    private static final int MAX_MESSAGE_CHARS = 16_000;
    private static final int MAX_TOTAL_CHARS = 96_000;

    public List<Msg> toMessages(RunContext context) {
        List<Msg> messages = new ArrayList<>();
        if (context.messages() != null) {
            for (RunMessage message : context.messages()) {
                if (message == null) {
                    continue;
                }
                String role = message.role() == null ? "" : message.role().toLowerCase(Locale.ROOT);
                String content = text(message.content(), message.toolResult());
                if (content.isBlank()) {
                    continue;
                }
                switch (role) {
                    case "user" -> messages.add(new UserMessage(limit(content)));
                    case "assistant" -> messages.add(new AssistantMessage(limit(content)));
                    case "system" -> messages.add(new SystemMessage(limit(content)));
                    case "tool" -> {
                        String toolCallId = message.toolCallId();
                        if (toolCallId != null && !toolCallId.isBlank()) {
                            messages.add(new ToolResultMessage(toolCallId, "tool", limit(content)));
                        }
                    }
                    default -> {
                        // 未知角色不进入模型上下文，避免把内部记录当作用户指令。
                    }
                }
            }
        }
        if (context.question() != null && !context.question().isBlank()) {
            String question = limit(context.question());
            boolean duplicate = !messages.isEmpty()
                    && messages.getLast() instanceof UserMessage
                    && question.equals(messages.getLast().getTextContent());
            if (!duplicate) {
                messages.add(new UserMessage(question));
            }
        }
        return trim(messages);
    }

    private static List<Msg> trim(List<Msg> messages) {
        if (messages.size() > MAX_MESSAGES) {
            messages = new ArrayList<>(messages.subList(messages.size() - MAX_MESSAGES, messages.size()));
        }
        while (messages.size() > 1 && totalChars(messages) > MAX_TOTAL_CHARS) {
            messages.removeFirst();
            while (!messages.isEmpty() && messages.getFirst() instanceof ToolResultMessage) {
                messages.removeFirst();
            }
        }
        return List.copyOf(messages);
    }

    private static int totalChars(List<Msg> messages) {
        int total = 0;
        for (Msg message : messages) {
            String text = message.getTextContent();
            total += text == null ? 0 : text.length();
        }
        return total;
    }

    private static String text(String content, String toolResult) {
        if (content != null && !content.isBlank()) {
            return content;
        }
        return toolResult == null ? "" : toolResult;
    }

    private static String limit(String text) {
        if (text.length() <= MAX_MESSAGE_CHARS) {
            return text;
        }
        return text.substring(0, MAX_MESSAGE_CHARS) + "\n\n[历史消息已截断]";
    }
}
