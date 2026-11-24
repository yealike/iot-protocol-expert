package top.yealike.pep.control.agents.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.control.agents.domain.AgentComponent;
import top.yealike.pep.control.agents.domain.AgentMcpBinding;
import top.yealike.pep.control.agents.domain.AgentRelease;
import top.yealike.pep.control.agents.domain.AgentSkillBinding;

public interface AgentPlatformMapper {

    /** 读取草稿的能力和委派策略。 */
    top.yealike.pep.control.agents.domain.AgentTeamSettings teamSettings(@Param("agentId") UUID agentId);

    /** 按草稿修订号保存能力和策略。 */
    int updateTeamSettings(
            @Param("agentId") UUID agentId,
            @Param("revision") long revision,
            @Param("settings") top.yealike.pep.control.agents.domain.AgentTeamSettings settings);

    /** 按固定顺序读取委派绑定。 */
    List<top.yealike.pep.runtime.contract.DelegateBinding> delegateBindings(@Param("agentId") UUID agentId);

    /** 清理指定草稿的委派绑定。 */
    int deleteDelegateBindings(@Param("agentId") UUID agentId);

    /** 批量创建已解析目标 Release 的草稿绑定。 */
    int insertDelegateBindings(
            @Param("agentId") UUID agentId,
            @Param("bindings") List<top.yealike.pep.runtime.contract.DelegateBinding> bindings);

    List<AgentComponent> list();

    List<AgentComponent> find(@Param("id") UUID id);

    List<AgentComponent> findByKey(@Param("key") String key);

    List<AgentComponent> lock(@Param("id") UUID id);

    int insert(
            @Param("id") UUID id,
            @Param("key") String key,
            @Param("displayName") String displayName,
            @Param("description") String description,
            @Param("modelProfileId") UUID modelProfileId,
            @Param("systemPrompt") String systemPrompt,
            @Param("modelOptionsJson") String modelOptionsJson,
            @Param("budgetJson") String budgetJson,
            @Param("actor") String actor);

    int update(
            @Param("id") UUID id,
            @Param("expectedRevision") long expectedRevision,
            @Param("displayName") String displayName,
            @Param("description") String description,
            @Param("modelProfileId") UUID modelProfileId,
            @Param("systemPrompt") String systemPrompt,
            @Param("modelOptionsJson") String modelOptionsJson,
            @Param("budgetJson") String budgetJson,
            @Param("enabled") boolean enabled);

    int setEnabled(@Param("id") UUID id, @Param("enabled") boolean enabled);

    int delete(@Param("id") UUID id);

    List<AgentMcpBinding> mcpBindings(@Param("agentId") UUID agentId);

    List<AgentSkillBinding> skillBindings(@Param("agentId") UUID agentId);

    int deleteMcpBindings(@Param("agentId") UUID agentId);

    int deleteSkillBindings(@Param("agentId") UUID agentId);

    int insertMcpBinding(
            @Param("agentId") UUID agentId,
            @Param("mcpReleaseId") UUID mcpReleaseId,
            @Param("toolName") String toolName,
            @Param("required") boolean required,
            @Param("executionOrder") int executionOrder);

    int insertSkillBinding(
            @Param("agentId") UUID agentId,
            @Param("skillReleaseId") UUID skillReleaseId,
            @Param("orderNo") int orderNo,
            @Param("loadMode") String loadMode);

    int insertRelease(
            @Param("id") UUID id,
            @Param("agentId") UUID agentId,
            @Param("version") String version,
            @Param("manifestJson") String manifestJson,
            @Param("manifestDigest") String manifestDigest,
            @Param("generation") long generation,
            @Param("actor") String actor);

    List<AgentRelease> findReleaseById(@Param("id") UUID id);

    List<AgentRelease> findReleaseByKeyAndVersion(@Param("key") String key, @Param("version") String version);

    List<AgentRelease> latestActive(@Param("agentId") UUID agentId);

    List<AgentRelease> releases(@Param("agentId") UUID agentId);

    List<AgentRelease> activeReleases();

    List<AgentRelease> defaultReleases();

    List<AgentRelease> desiredReleases();

    List<AgentRelease> findPublishedRelease(@Param("agentKey") String agentKey);

    Long nextGeneration(@Param("agentId") UUID agentId);

    Long releaseCount(@Param("agentId") UUID agentId);

    Long conversationReferenceCount(@Param("agentId") UUID agentId);

    int setReleaseState(@Param("releaseId") UUID releaseId, @Param("state") String state);

    int publishComponent(
            @Param("agentId") UUID agentId,
            @Param("version") String version,
            @Param("expectedGeneration") long expectedGeneration);

    String publishedVersion(@Param("agentKey") String agentKey);
}
