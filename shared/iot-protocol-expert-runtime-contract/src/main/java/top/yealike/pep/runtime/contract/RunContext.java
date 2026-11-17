package top.yealike.pep.runtime.contract;

import java.util.List;
import java.util.UUID;

/** 一 Run 的受控上下文；发送者由 Control 鉴权后填充，Runtime 不自行拼接。 */
public record RunContext(UUID runId, UUID conversationId, String question, List<RunMessage> messages) {

    public RunContext {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
