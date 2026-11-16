package top.yealike.ipe.ai.platform.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import top.yealike.ipe.ai.contract.AgentManifest;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.AgentComponent;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.AgentMcpBinding;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.AgentRelease;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.AgentSkillBinding;
import top.yealike.ipe.ai.support.JsonSupport;

@Repository
public class AgentStore {

    private static final String SELECT =
            "select id, component_key, display_name, description, model_profile_id, system_prompt, model_options, budget_json, published_version, publication_generation, enabled, revision, created_by, created_at, updated_at from ai_agent_component";

    private final JdbcTemplate jdbc;

    public AgentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AgentComponent> list() {
        return jdbc.query(SELECT + " order by component_key", (rows, index) -> map(rows));
    }

    public Optional<AgentComponent> find(UUID id) {
        return jdbc.query(SELECT + " where id = ?", (rows, index) -> map(rows), id).stream()
                .findFirst();
    }

    public Optional<AgentComponent> findByKey(String key) {
        return jdbc.query(SELECT + " where component_key = ?", (rows, index) -> map(rows), key).stream()
                .findFirst();
    }

    public Optional<AgentComponent> lock(UUID id) {
        return jdbc.query(SELECT + " where id = ? for update", (rows, index) -> map(rows), id).stream()
                .findFirst();
    }

    public UUID insert(
            String key,
            String displayName,
            String description,
            UUID modelProfileId,
            String systemPrompt,
            String modelOptionsJson,
            String budgetJson,
            String actor) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into ai_agent_component (id, component_key, display_name, description, model_profile_id, system_prompt, model_options, budget_json, enabled, revision, created_by) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), true, 0, ?)",
                id,
                key,
                displayName,
                description,
                modelProfileId,
                systemPrompt,
                modelOptionsJson,
                budgetJson,
                actor);
        return id;
    }

    public int update(
            UUID id,
            long expectedRevision,
            String displayName,
            String description,
            UUID modelProfileId,
            String systemPrompt,
            String modelOptionsJson,
            String budgetJson,
            boolean enabled) {
        return jdbc.update(
                "update ai_agent_component set display_name = ?, description = ?, model_profile_id = ?, system_prompt = ?, model_options = cast(? as jsonb), budget_json = cast(? as jsonb), enabled = ?, revision = revision + 1, updated_at = now() where id = ? and revision = ?",
                displayName,
                description,
                modelProfileId,
                systemPrompt,
                modelOptionsJson,
                budgetJson,
                enabled,
                id,
                expectedRevision);
    }

    public int setEnabled(UUID id, boolean enabled) {
        return jdbc.update(
                "update ai_agent_component set enabled = ?, revision = revision + 1, updated_at = now() where id = ?",
                enabled,
                id);
    }

    public int delete(UUID id) {
        return jdbc.update("delete from ai_agent_component where id = ?", id);
    }

    public List<AgentMcpBinding> mcpBindings(UUID agentId) {
        return jdbc.query(
                """
                select b.mcp_release_id, c.component_key, r.version, b.tool_name, b.required, b.execution_order
                  from ai_agent_mcp_binding b
                  join ai_mcp_release r on r.id = b.mcp_release_id
                  join ai_mcp_component c on c.id = r.component_id
                 where b.agent_id = ?
                 order by b.execution_order, c.component_key, b.tool_name
                """,
                (rows, index) -> new AgentMcpBinding(
                        JdbcRows.uuid(rows, "mcp_release_id"),
                        rows.getString("component_key"),
                        rows.getString("version"),
                        rows.getString("tool_name"),
                        rows.getBoolean("required"),
                        rows.getInt("execution_order"),
                        null),
                agentId);
    }

    public List<AgentSkillBinding> skillBindings(UUID agentId) {
        return jdbc.query(
                """
                select b.skill_release_id, c.component_key, r.version, b.order_no, b.load_mode, r.bundle_digest,
                       r.name, r.description
                  from ai_agent_skill_binding b
                  join ai_skill_release r on r.id = b.skill_release_id
                  join ai_skill_component c on c.id = r.skill_id
                 where b.agent_id = ?
                 order by b.order_no, c.component_key
                """,
                (rows, index) -> new AgentSkillBinding(
                        JdbcRows.uuid(rows, "skill_release_id"),
                        rows.getString("component_key"),
                        rows.getString("version"),
                        rows.getInt("order_no"),
                        rows.getString("load_mode"),
                        rows.getString("bundle_digest"),
                        rows.getString("name"),
                        rows.getString("description")),
                agentId);
    }

    public void replaceBindings(UUID agentId, List<McpBindingInput> mcp, List<SkillBindingInput> skills) {
        jdbc.update("delete from ai_agent_mcp_binding where agent_id = ?", agentId);
        jdbc.update("delete from ai_agent_skill_binding where agent_id = ?", agentId);
        for (McpBindingInput binding : mcp) {
            jdbc.update(
                    "insert into ai_agent_mcp_binding (agent_id, mcp_release_id, tool_name, required, execution_order) values (?, ?, ?, ?, ?)",
                    agentId,
                    binding.mcpReleaseId(),
                    binding.toolName(),
                    binding.required(),
                    binding.executionOrder());
        }
        for (SkillBindingInput binding : skills) {
            jdbc.update(
                    "insert into ai_agent_skill_binding (agent_id, skill_release_id, order_no, load_mode) values (?, ?, ?, ?)",
                    agentId,
                    binding.skillReleaseId(),
                    binding.order(),
                    binding.loadMode());
        }
        jdbc.update("update ai_agent_component set revision = revision + 1, updated_at = now() where id = ?", agentId);
    }

    public UUID insertRelease(
            UUID agentId, String version, AgentManifest manifest, String digest, long generation, String actor) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into ai_agent_release (id, agent_id, version, manifest_json, manifest_digest, state, generation, created_by) values (?, ?, ?, cast(? as jsonb), ?, 'STAGED', ?, ?)",
                id,
                agentId,
                version,
                JsonSupport.write(manifest),
                digest,
                generation,
                actor);
        return id;
    }

    public Optional<AgentRelease> findRelease(UUID id) {
        return jdbc.query("""
                        select r.id, r.agent_id, c.component_key, r.version, r.manifest_json, r.manifest_digest,
                               r.state, r.generation, r.release_cursor, r.created_at
                          from ai_agent_release r join ai_agent_component c on c.id = r.agent_id
                         where r.id = ?
                        """, (rows, index) -> mapRelease(rows), id).stream().findFirst();
    }

    public Optional<AgentRelease> findRelease(String key, String version) {
        return jdbc.query("""
                        select r.id, r.agent_id, c.component_key, r.version, r.manifest_json, r.manifest_digest,
                               r.state, r.generation, r.release_cursor, r.created_at
                          from ai_agent_release r join ai_agent_component c on c.id = r.agent_id
                         where c.component_key = ? and r.version = ?
                        """, (rows, index) -> mapRelease(rows), key, version).stream()
                .findFirst();
    }

    public Optional<AgentRelease> latestActive(UUID agentId) {
        return jdbc.query("""
                        select r.id, r.agent_id, c.component_key, r.version, r.manifest_json, r.manifest_digest,
                               r.state, r.generation, r.release_cursor, r.created_at
                          from ai_agent_release r join ai_agent_component c on c.id = r.agent_id
                         where r.agent_id = ? and r.state = 'ACTIVE'
                         order by r.created_at desc limit 1
                        """, (rows, index) -> mapRelease(rows), agentId).stream()
                .findFirst();
    }

    public List<AgentRelease> releases(UUID agentId) {
        return jdbc.query("""
                select r.id, r.agent_id, c.component_key, r.version, r.manifest_json, r.manifest_digest,
                       r.state, r.generation, r.release_cursor, r.created_at
                  from ai_agent_release r join ai_agent_component c on c.id = r.agent_id
                 where r.agent_id = ? order by r.created_at desc
                """, (rows, index) -> mapRelease(rows), agentId);
    }

    public List<AgentRelease> activeReleases() {
        return jdbc.query("""
                select r.id, r.agent_id, c.component_key, r.version, r.manifest_json, r.manifest_digest,
                       r.state, r.generation, r.release_cursor, r.created_at
                  from ai_agent_release r join ai_agent_component c on c.id = r.agent_id
                 where r.state = 'ACTIVE' and c.enabled = true order by c.component_key
                """, (rows, index) -> mapRelease(rows));
    }

    /** 用户目录只返回每个 Agent 当前默认版本，避免同一 key 出现多个可选项。 */
    public List<AgentRelease> defaultReleases() {
        return jdbc.query("""
                select r.id, r.agent_id, c.component_key, r.version, r.manifest_json, r.manifest_digest,
                       r.state, r.generation, r.release_cursor, r.created_at
                  from ai_agent_release r join ai_agent_component c on c.id = r.agent_id
                 where c.enabled = true and r.state = 'ACTIVE' and c.published_version = r.version
                 order by r.release_cursor
                """, (rows, index) -> mapRelease(rows));
    }

    /** Runtime 对账返回默认版本和仍被会话或在途 Run 引用的可服务历史版本。 */
    public List<AgentRelease> desiredReleases() {
        return jdbc.query("""
                select r.id, r.agent_id, c.component_key, r.version, r.manifest_json, r.manifest_digest,
                       r.state, r.generation, r.release_cursor, r.created_at
                  from ai_agent_release r join ai_agent_component c on c.id = r.agent_id
                 where c.enabled = true and r.state = 'ACTIVE' and (
                       c.published_version = r.version
                       or r.id in (
                           select agent_release_id from ai_conversation where agent_release_id is not null
                           union all
                           select agent_release_id
                             from ai_run
                            where agent_release_id is not null
                              and status in ('QUEUED', 'DISPATCHING', 'RUNNING', 'CANCEL_REQUESTED')
                       ))
                 order by r.release_cursor
                """, (rows, index) -> mapRelease(rows));
    }

    public Optional<AgentRelease> findPublishedRelease(String agentKey) {
        return jdbc.query("""
                        select r.id, r.agent_id, c.component_key, r.version, r.manifest_json, r.manifest_digest,
                               r.state, r.generation, r.release_cursor, r.created_at
                          from ai_agent_release r join ai_agent_component c on c.id = r.agent_id
                         where c.component_key = ? and c.enabled = true and c.published_version = r.version
                           and r.state = 'ACTIVE'
                        """, (rows, index) -> mapRelease(rows), agentKey).stream()
                .findFirst();
    }

    public long nextGeneration(UUID agentId) {
        Long value = jdbc.queryForObject(
                "select coalesce(max(generation), 0) + 1 from ai_agent_release where agent_id = ?",
                Long.class,
                agentId);
        return value == null ? 1 : value;
    }

    public long releaseCount(UUID agentId) {
        Long value =
                jdbc.queryForObject("select count(*) from ai_agent_release where agent_id = ?", Long.class, agentId);
        return value == null ? 0 : value;
    }

    public long conversationReferenceCount(UUID agentId) {
        Long value = jdbc.queryForObject("""
                select count(*)
                  from ai_conversation c
                 where c.agent_release_id in (select id from ai_agent_release where agent_id = ?)
                """, Long.class, agentId);
        return value == null ? 0 : value;
    }

    public int setReleaseState(UUID releaseId, String state) {
        return jdbc.update(
                "update ai_agent_release set state = ? where id = ? and state in ('STAGED', 'ACTIVE', 'RETIRED')",
                state,
                releaseId);
    }

    public boolean publishComponent(UUID agentId, String version, long expectedGeneration) {
        return jdbc.update(
                        """
                        update ai_agent_component c
                           set published_version = ?, publication_generation = ?,
                               revision = revision + 1, updated_at = now()
                         where c.id = ?
                           and (publication_generation < ?
                                or (publication_generation = ? and published_version = ?))
                           and exists (
                               select 1
                                 from ai_agent_release r
                                where r.agent_id = c.id
                                  and r.version = ?
                                  and r.state = 'ACTIVE'
                           )
                        """,
                        version,
                        expectedGeneration,
                        agentId,
                        expectedGeneration,
                        expectedGeneration,
                        version,
                        version)
                > 0;
    }

    public Optional<String> publishedVersion(String agentKey) {
        return jdbc
                .query(
                        "select published_version from ai_agent_component where component_key = ? and enabled = true",
                        (rows, index) -> rows.getString("published_version"),
                        agentKey)
                .stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    private AgentComponent map(java.sql.ResultSet rows) throws java.sql.SQLException {
        return new AgentComponent(
                JdbcRows.uuid(rows, "id"),
                rows.getString("component_key"),
                rows.getString("display_name"),
                rows.getString("description"),
                JdbcRows.uuid(rows, "model_profile_id"),
                rows.getString("system_prompt"),
                JsonSupport.readMap(rows.getString("model_options")),
                JsonSupport.readMap(rows.getString("budget_json")),
                rows.getString("published_version"),
                rows.getLong("publication_generation"),
                rows.getBoolean("enabled"),
                rows.getLong("revision"),
                rows.getString("created_by"),
                JdbcRows.instant(rows, "created_at"),
                JdbcRows.instant(rows, "updated_at"));
    }

    private AgentRelease mapRelease(java.sql.ResultSet rows) throws java.sql.SQLException {
        return new AgentRelease(
                JdbcRows.uuid(rows, "id"),
                JdbcRows.uuid(rows, "agent_id"),
                rows.getString("component_key"),
                rows.getString("version"),
                JsonSupport.read(rows.getString("manifest_json"), AgentManifest.class),
                rows.getString("manifest_digest"),
                rows.getString("state"),
                rows.getLong("generation"),
                rows.getLong("release_cursor"),
                JdbcRows.instant(rows, "created_at"));
    }

    public record McpBindingInput(UUID mcpReleaseId, String toolName, boolean required, int executionOrder) {}

    public record SkillBindingInput(UUID skillReleaseId, int order, String loadMode) {}
}
