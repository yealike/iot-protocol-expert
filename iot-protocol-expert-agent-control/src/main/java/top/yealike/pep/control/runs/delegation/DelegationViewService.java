package top.yealike.pep.control.runs.delegation;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import top.yealike.pep.control.access.policy.PolicyService;
import top.yealike.pep.control.runs.mapper.A2aTaskMapper;
import top.yealike.pep.runtime.contract.UserIdentity;

/** 从持久化 Task 读取可重放的会话委派摘要。 */
@Service
public class DelegationViewService {

    private static final int MAX_VISIBLE_TASKS = 200;

    private final A2aTaskMapper tasks;
    private final PolicyService policy;

    public DelegationViewService(A2aTaskMapper tasks, PolicyService policy) {
        this.tasks = tasks;
        this.policy = policy;
    }

    /** 在会话已授权后，再按调用者对目标 Agent 的权限过滤任务。 */
    public List<DelegationView> list(UserIdentity user, UUID conversationId) {
        return tasks.byConversation(conversationId, MAX_VISIBLE_TASKS).stream()
                .filter(task -> policy.canUseAgent(user, task.targetAgentKey()).allowed())
                .toList()
                .reversed();
    }
}
