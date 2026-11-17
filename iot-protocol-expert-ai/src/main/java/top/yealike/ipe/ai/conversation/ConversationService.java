package top.yealike.ipe.ai.conversation;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import top.yealike.ipe.ai.conversation.ConversationRecords.Conversation;
import top.yealike.ipe.ai.conversation.ConversationRecords.ConversationView;
import top.yealike.ipe.ai.conversation.ConversationRecords.Message;
import top.yealike.ipe.ai.identity.UserIdentity;
import top.yealike.ipe.ai.platform.agent.AgentService;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.AgentRelease;
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
    private final AgentService agents;

    public ConversationService(
            ConversationRepository conversations, RunRepository runs, PolicyService policy, AgentService agents) {
        this.conversations = conversations;
        this.runs = runs;
        this.policy = policy;
        this.agents = agents;
    }

    public Conversation create(UserIdentity identity, String agentKey, String version, String title) {
        return create(identity, agentKey, version, title, false);
    }

    public Conversation createEvaluation(UserIdentity identity, String agentKey, String version, String title) {
        return create(identity, agentKey, version, title, true);
    }

    private Conversation create(
            UserIdentity identity, String agentKey, String version, String title, boolean evaluation) {
        if (agentKey == null || agentKey.isBlank()) {
            throw AiExceptions.badRequest("必须指定 Agent");
        }
        String key = agentKey;
        PolicyService.Decision decision = policy.canUseAgent(identity, key);
        if (!decision.allowed()) {
            throw AiExceptions.forbidden("AGENT_DENIED", decision.reason());
        }
        AgentRelease release = agents.requirePublishedRelease(key, version);
        Instant now = Instant.now();
        String finalTitle = title == null || title.isBlank()
                ? release.manifest().displayName() + " · " + TITLE_TIME.format(now)
                : title.strip();
        if (finalTitle.length() > 200) {
            finalTitle = finalTitle.substring(0, 200);
        }
        Conversation conversation = new Conversation(
                UUID.randomUUID(),
                identity.actor(),
                null,
                finalTitle,
                release.agentKey(),
                release.version(),
                release.id(),
                release.manifestDigest(),
                "ACTIVE",
                0,
                now,
                now,
                null);
        if (evaluation) {
            conversations.insertEvaluation(conversation);
        } else {
            conversations.insert(conversation);
        }
        return conversation;
    }

    public List<Conversation> list(UserIdentity identity, int page, int size) {
        int normalizedSize = Math.min(Math.max(size, 1), 100);
        long offset = (long) Math.max(page, 0) * normalizedSize;
        if (offset > Integer.MAX_VALUE) {
            throw AiExceptions.badRequest("页码超出范围");
        }
        return conversations.listByUser(identity.actor(), normalizedSize, (int) offset);
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
