package top.yealike.pep.control.runs.mapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.control.runs.delegation.A2aTask;
import top.yealike.pep.control.runs.delegation.A2aTaskCounts;
import top.yealike.pep.control.runs.delegation.A2aTaskTransition;
import top.yealike.pep.control.runs.delegation.DelegationView;

/** 持久化 Task、任务事件和本地子 Run。 */
public interface A2aTaskMapper {

    /** 保存 Task 与创建时分配的子 Run 标识。 */
    int insertTask(@Param("task") A2aTask task, @Param("requestJson") String requestJson);

    /** 从父 Run 复制用户身份与策略快照，写入独立子 Run。 */
    int insertChildRun(
            @Param("task") A2aTask task,
            @Param("messageId") UUID messageId,
            @Param("agentKey") String agentKey,
            @Param("agentVersion") String agentVersion,
            @Param("manifestDigest") String manifestDigest,
            @Param("modelKey") String modelKey,
            @Param("question") String question,
            @Param("leaseUntil") Instant leaseUntil);

    /** 从父会话继承归属，写入隔离的子会话。 */
    int insertChildConversation(
            @Param("task") A2aTask task,
            @Param("parentConversationId") UUID parentConversationId,
            @Param("title") String title,
            @Param("agentKey") String agentKey,
            @Param("agentVersion") String agentVersion,
            @Param("manifestDigest") String manifestDigest);

    /** 查询 Task。 */
    A2aTask find(@Param("id") UUID id);

    /** 锁定 Task，按版本同步子 Run 状态。 */
    A2aTask lock(@Param("id") UUID id);

    /** 在父 Run 范围内查找幂等请求。 */
    A2aTask findByKey(@Param("parentRunId") UUID parentRunId, @Param("key") String key);

    /** 统计父 Run 的委派次数及并发。 */
    A2aTaskCounts counts(@Param("parentRunId") UUID parentRunId, @Param("alias") String alias);

    /** 统计其他根 Run 正在执行的委派链数。 */
    int activeRoots(@Param("rootRunId") UUID rootRunId);

    /** 统计当前根 Run 下已经完成的模型 Token。 */
    long usedTokens(@Param("rootRunId") UUID rootRunId);

    /** 原子迁移尚未终结的 Task。 */
    int transition(A2aTaskTransition transition);

    /** 追加一条与状态版本对应的事件。 */
    int insertEvent(@Param("taskId") UUID taskId, @Param("seq") long seq, @Param("type") String type);

    /** 查询存在子 Run 状态变化的 Task。 */
    List<UUID> pendingSync(@Param("limit") int limit);

    /** 找出父 Run 已经停止的活跃子 Run。 */
    List<UUID> orphanRuns(@Param("limit") int limit);

    /** 获取会话关联的 Task，按最近创建排序。 */
    List<DelegationView> byConversation(@Param("conversationId") UUID conversationId, @Param("limit") int limit);
}
