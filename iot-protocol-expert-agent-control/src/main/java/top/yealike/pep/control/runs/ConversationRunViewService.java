package top.yealike.pep.control.runs;

import java.util.UUID;
import org.springframework.stereotype.Service;
import top.yealike.pep.control.conversations.ConversationService;
import top.yealike.pep.control.runs.domain.ConversationView;
import top.yealike.pep.control.runs.domain.Run;
import top.yealike.pep.runtime.contract.UserIdentity;

/** 在 Run 所属功能内组装会话详情，避免 conversations 反向依赖 runs。 */
@Service
public class ConversationRunViewService {

    private final ConversationService conversations;
    private final RunRepository runs;

    public ConversationRunViewService(ConversationService conversations, RunRepository runs) {
        this.conversations = conversations;
        this.runs = runs;
    }

    public ConversationView detail(UserIdentity identity, UUID conversationId) {
        var detail = conversations.detail(identity, conversationId);
        Run currentRun = runs.current(conversationId).orElse(null);
        Run latestRun = currentRun == null
                ? runs.listByConversation(conversationId, 1).stream()
                        .findFirst()
                        .orElse(null)
                : currentRun;
        return new ConversationView(detail.conversation(), detail.messages(), currentRun, latestRun);
    }
}
