package top.yealike.ipe.ai.conversation;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import top.yealike.ipe.ai.agent.AgentCatalog;
import top.yealike.ipe.ai.conversation.ConversationRecords.Conversation;
import top.yealike.ipe.ai.conversation.ConversationRecords.ConversationView;
import top.yealike.ipe.ai.conversation.ConversationRecords.Message;
import top.yealike.ipe.ai.identity.UserIdentity;
import top.yealike.ipe.ai.policy.PolicyService;
import top.yealike.ipe.ai.run.RunRepository;
import top.yealike.ipe.ai.support.AiExceptions;

/** 会话生命周期：仅本人可见，暂不做共享；每次访问都做归属校验。 */
@Service
public class ConversationService {

    private static final DateTimeFormatter TITLE_TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    private final ConversationRepository conversations;
    private final RunRepository runs;
    private final PolicyService policy;
    private final AgentCatalog catalog;

    public ConversationService(
            ConversationRepository conversations, RunRepository runs, PolicyService policy, AgentCatalog catalog) {
        this.conversations = conversations;
        this.runs = runs;
        this.policy = policy;
        this.catalog = catalog;
    }

    public Conversation create(UserIdentity identity, String agentKey, String title) {
        String key = agentKey == null || agentKey.isBlank() ? AgentCatalog.TEAM_LEADER : agentKey;
        PolicyService.Decision decision = policy.canUseAgent(identity, key);
        if (!decision.allowed()) {
            throw AiExceptions.forbidden("AGENT_DENIED", decision.reason());
        }
        AgentCatalog.Definition definition = catalog.find(key);
        if (definition == null) {
            throw AiExceptions.notFound("Agent 未发布：" + key);
        }
        Instant now = Instant.now();
        String finalTitle =
                title == null || title.isBlank() ? definition.name() + " · " + TITLE_TIME.format(now) : title.strip();
        if (finalTitle.length() > 200) {
            finalTitle = finalTitle.substring(0, 200);
        }
        Conversation conversation = new Conversation(
                UUID.randomUUID(),
                identity.actor(),
                null,
                finalTitle,
                definition.agentKey(),
                definition.version(),
                "ACTIVE",
                0,
                now,
                now,
                null);
        conversations.insert(conversation);
        return conversation;
    }

    public List<Conversation> list(UserIdentity identity, int page, int size) {
        int normalizedSize = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * normalizedSize;
        return conversations.listByUser(identity.actor(), normalizedSize, offset);
    }

    public long count(UserIdentity identity) {
        return conversations.countByUser(identity.actor());
    }

    public ConversationView detail(UserIdentity identity, UUID conversationId) {
        Conversation conversation = require(identity, conversationId);
        List<Message> messages = conversations.listMessages(conversationId, 200);
        Object currentRun = runs.current(conversationId)
                .map(run -> (Object) runs.find(run.id()).orElse(run))
                .orElse(null);
        return new ConversationView(conversation, messages, currentRun);
    }

    public Conversation require(UserIdentity identity, UUID conversationId) {
        Conversation conversation = conversations
                .find(conversationId)
                .filter(item -> item.userId().equals(identity.actor()))
                .orElseThrow(() -> AiExceptions.notFound("会话不存在"));
        policy.reload();
        if (!policy.canUseAgent(identity, conversation.agentKey()).allowed()) {
            throw AiExceptions.forbidden("AGENT_DENIED", "当前账号无权访问该会话使用的 Agent");
        }
        return conversation;
    }
}
