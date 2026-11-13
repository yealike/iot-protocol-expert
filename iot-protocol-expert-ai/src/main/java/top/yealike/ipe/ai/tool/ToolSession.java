package top.yealike.ipe.ai.tool;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import top.yealike.ipe.ai.conversation.ConversationRecords.Citation;
import top.yealike.ipe.ai.identity.UserIdentity;

/**
 * 一次 Run 的工具上下文。
 *
 * <p>每个 Run 独立实例，工具对象与 Toolkit 也按 Run 构建，避免跨请求共享可变状态； 子 Agent 通过 {@link #forAgent(String)} 派生，共享预算、引用与告警，因而子 Agent 的调用计入同一 Run。
 */
public final class ToolSession {

    private final UserIdentity identity;
    private final UUID conversationId;
    private final UUID runId;
    private final String agentKey;
    private final String policyVersion;
    private final String stationScope;
    private final int maxToolCalls;
    private final AtomicInteger calls;
    private final List<Citation> citations;
    private final List<String> warnings;

    public ToolSession(
            UserIdentity identity,
            UUID conversationId,
            UUID runId,
            String agentKey,
            String policyVersion,
            String stationScope,
            int maxToolCalls) {
        this(
                identity,
                conversationId,
                runId,
                agentKey,
                policyVersion,
                stationScope,
                maxToolCalls,
                new AtomicInteger(),
                new CopyOnWriteArrayList<>(),
                new CopyOnWriteArrayList<>());
    }

    private ToolSession(
            UserIdentity identity,
            UUID conversationId,
            UUID runId,
            String agentKey,
            String policyVersion,
            String stationScope,
            int maxToolCalls,
            AtomicInteger calls,
            List<Citation> citations,
            List<String> warnings) {
        this.identity = identity;
        this.conversationId = conversationId;
        this.runId = runId;
        this.agentKey = agentKey;
        this.policyVersion = policyVersion;
        this.stationScope = stationScope;
        this.maxToolCalls = maxToolCalls;
        this.calls = calls;
        this.citations = citations;
        this.warnings = warnings;
    }

    /** 派生同一 Run 下另一个 Agent 的工具上下文：身份、预算、引用共享，Agent 身份不同。 */
    public ToolSession forAgent(String nextAgentKey) {
        return new ToolSession(
                identity,
                conversationId,
                runId,
                nextAgentKey,
                policyVersion,
                stationScope,
                maxToolCalls,
                calls,
                citations,
                warnings);
    }

    public UserIdentity identity() {
        return identity;
    }

    public UUID conversationId() {
        return conversationId;
    }

    public UUID runId() {
        return runId;
    }

    public String agentKey() {
        return agentKey;
    }

    public String policyVersion() {
        return policyVersion;
    }

    public String stationScope() {
        return stationScope;
    }

    public int maxToolCalls() {
        return maxToolCalls;
    }

    public boolean tryAcquireCall() {
        while (true) {
            int current = calls.get();
            if (current >= maxToolCalls) {
                return false;
            }
            if (calls.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public int callCount() {
        return calls.get();
    }

    public void addCitation(Citation citation) {
        if (citation != null
                && citations.stream()
                        .noneMatch(item -> item.documentId().equals(citation.documentId())
                                && java.util.Objects.equals(item.pageNo(), citation.pageNo())
                                && java.util.Objects.equals(item.sectionPath(), citation.sectionPath()))) {
            citations.add(citation);
        }
    }

    public List<Citation> citations() {
        return List.copyOf(citations);
    }

    public void addWarning(String warning) {
        if (warning != null && !warning.isBlank() && !warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public Set<String> roles() {
        return Set.copyOf(identity.roles());
    }
}
