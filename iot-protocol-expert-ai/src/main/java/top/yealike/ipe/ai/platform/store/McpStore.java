package top.yealike.ipe.ai.platform.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import top.yealike.ipe.ai.contract.McpToolDescriptor;
import top.yealike.ipe.ai.contract.McpToolExecutionCategory;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.McpComponent;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.McpRelease;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.McpTestReport;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.McpToolPolicy;
import top.yealike.ipe.ai.support.JsonSupport;

@Repository
public class McpStore {

    private static final String COMPONENT_SELECT =
            "select id, component_key, display_name, description, origin, native_registry_ref, draft_json, published_version, publication_generation, enabled, revision, created_by, created_at, updated_at from ai_mcp_component";

    private final JdbcTemplate jdbc;

    public McpStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<McpComponent> list() {
        return jdbc.query(COMPONENT_SELECT + " order by component_key", (rows, index) -> mapComponent(rows));
    }

    public Optional<McpComponent> find(UUID id) {
        return jdbc.query(COMPONENT_SELECT + " where id = ?", (rows, index) -> mapComponent(rows), id).stream()
                .findFirst();
    }

    public Optional<McpComponent> findByKey(String key) {
        return jdbc
                .query(COMPONENT_SELECT + " where component_key = ?", (rows, index) -> mapComponent(rows), key)
                .stream()
                .findFirst();
    }

    public Optional<McpComponent> lock(UUID id) {
        return jdbc
                .query(COMPONENT_SELECT + " where id = ? for update", (rows, index) -> mapComponent(rows), id)
                .stream()
                .findFirst();
    }

    public UUID insert(
            String key,
            String displayName,
            String description,
            String origin,
            String nativeRegistryRefJson,
            String draftJson,
            String actor) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into ai_mcp_component (id, component_key, display_name, description, origin, native_registry_ref, draft_json, enabled, revision, created_by) values (?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), true, 0, ?)",
                id,
                key,
                displayName,
                description,
                origin,
                nativeRegistryRefJson,
                draftJson,
                actor);
        return id;
    }

    public int update(
            UUID id,
            long expectedRevision,
            String displayName,
            String description,
            String nativeRegistryRefJson,
            String draftJson,
            boolean enabled) {
        return jdbc.update(
                "update ai_mcp_component set display_name = ?, description = ?, native_registry_ref = cast(? as jsonb), draft_json = cast(? as jsonb), enabled = ?, revision = revision + 1, updated_at = now() where id = ? and revision = ?",
                displayName,
                description,
                nativeRegistryRefJson,
                draftJson,
                enabled,
                id,
                expectedRevision);
    }

    public int setEnabled(UUID id, boolean enabled) {
        return jdbc.update(
                "update ai_mcp_component set enabled = ?, revision = revision + 1, updated_at = now() where id = ?",
                enabled,
                id);
    }

    public int delete(UUID id) {
        return jdbc.update("delete from ai_mcp_component where id = ?", id);
    }

    public List<McpToolPolicy> toolPolicies(UUID mcpId) {
        return jdbc.query(
                """
                select mcp_id, tool_name, execution_category, reviewed_by, reviewed_at
                  from ai_mcp_tool_policy
                 where mcp_id = ?
                 order by tool_name
                """,
                (rows, index) -> new McpToolPolicy(
                        JdbcRows.uuid(rows, "mcp_id"),
                        rows.getString("tool_name"),
                        McpToolExecutionCategory.parse(rows.getString("execution_category")),
                        rows.getString("reviewed_by"),
                        JdbcRows.instant(rows, "reviewed_at")),
                mcpId);
    }

    public Map<String, McpToolExecutionCategory> toolCategories(UUID mcpId) {
        return jdbc.query(
                "select tool_name, execution_category from ai_mcp_tool_policy where mcp_id = ?",
                rows -> {
                    Map<String, McpToolExecutionCategory> result = new java.util.HashMap<>();
                    while (rows.next()) {
                        result.put(
                                rows.getString("tool_name"),
                                McpToolExecutionCategory.parse(rows.getString("execution_category")));
                    }
                    return Map.copyOf(result);
                },
                mcpId);
    }

    public void upsertToolPolicy(UUID mcpId, String toolName, McpToolExecutionCategory category, String reviewedBy) {
        jdbc.update("""
                insert into ai_mcp_tool_policy (
                    mcp_id, tool_name, execution_category, reviewed_by, reviewed_at)
                values (?, ?, ?, ?, now())
                on conflict (mcp_id, tool_name) do update
                   set execution_category = excluded.execution_category,
                       reviewed_by = excluded.reviewed_by,
                       reviewed_at = now()
                """, mcpId, toolName, category.name(), reviewedBy);
    }

    public int deleteToolPolicies(UUID mcpId) {
        return jdbc.update("delete from ai_mcp_tool_policy where mcp_id = ?", mcpId);
    }

    public long bindingReferences(UUID componentId) {
        Long count = jdbc.queryForObject(
                "select count(*) from ai_agent_mcp_binding b join ai_mcp_release r on r.id = b.mcp_release_id where r.component_id = ?",
                Long.class,
                componentId);
        return count == null ? 0 : count;
    }

    public UUID insertRelease(
            UUID componentId,
            String version,
            long generation,
            String transport,
            String endpointPolicyJson,
            UUID credentialId,
            List<McpToolDescriptor> tools,
            String catalogDigest,
            String contentDigest,
            String actor) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into ai_mcp_release (id, component_id, version, generation, transport, endpoint_policy, credential_id, tool_catalog, catalog_digest, content_digest, state, created_by) values (?, ?, ?, ?, ?, cast(? as jsonb), ?, cast(? as jsonb), ?, ?, 'STAGED', ?)",
                id,
                componentId,
                version,
                generation,
                transport,
                endpointPolicyJson,
                credentialId,
                JsonSupport.write(tools),
                catalogDigest,
                contentDigest,
                actor);
        return id;
    }

    public Optional<McpRelease> findRelease(UUID id) {
        return jdbc.query("""
                        select r.id, r.component_id, c.component_key, r.version, r.transport, r.endpoint_policy,
                               r.credential_id, r.tool_catalog, r.catalog_digest, r.content_digest, r.state,
                               r.generation, r.created_at
                          from ai_mcp_release r join ai_mcp_component c on c.id = r.component_id
                         where r.id = ?
                        """, (rows, index) -> mapRelease(rows), id).stream().findFirst();
    }

    public Optional<McpRelease> findRelease(String key, String version) {
        return jdbc.query("""
                        select r.id, r.component_id, c.component_key, r.version, r.transport, r.endpoint_policy,
                               r.credential_id, r.tool_catalog, r.catalog_digest, r.content_digest, r.state,
                               r.generation, r.created_at
                          from ai_mcp_release r join ai_mcp_component c on c.id = r.component_id
                         where c.component_key = ? and r.version = ?
                        """, (rows, index) -> mapRelease(rows), key, version).stream()
                .findFirst();
    }

    public Optional<McpRelease> latestActive(UUID componentId) {
        return jdbc.query("""
                        select r.id, r.component_id, c.component_key, r.version, r.transport, r.endpoint_policy,
                               r.credential_id, r.tool_catalog, r.catalog_digest, r.content_digest, r.state,
                               r.generation, r.created_at
                          from ai_mcp_release r join ai_mcp_component c on c.id = r.component_id
                         where r.component_id = ? and r.state = 'ACTIVE'
                         order by r.created_at desc limit 1
                        """, (rows, index) -> mapRelease(rows), componentId).stream()
                .findFirst();
    }

    public List<McpRelease> releases(UUID componentId) {
        return jdbc.query("""
                select r.id, r.component_id, c.component_key, r.version, r.transport, r.endpoint_policy,
                       r.credential_id, r.tool_catalog, r.catalog_digest, r.content_digest, r.state,
                       r.generation, r.created_at
                  from ai_mcp_release r join ai_mcp_component c on c.id = r.component_id
                 where r.component_id = ? order by r.created_at desc
                """, (rows, index) -> mapRelease(rows), componentId);
    }

    public int setReleaseState(UUID releaseId, String state) {
        return jdbc.update(
                "update ai_mcp_release set state = ? where id = ? and state in ('STAGED', 'ACTIVE')", state, releaseId);
    }

    public boolean publishComponent(UUID componentId, String version, long expectedGeneration) {
        int updated = jdbc.update(
                """
                        update ai_mcp_component c
                           set published_version = ?, publication_generation = ?, revision = revision + 1, updated_at = now()
                         where c.id = ?
                           and publication_generation < ? and exists (
                               select 1
                                 from ai_mcp_release r
                                where r.component_id = c.id
                                  and r.version = ?
                                  and r.generation = ?
                                  and r.state = 'ACTIVE'
                           )
                        """, version, expectedGeneration, componentId, expectedGeneration, version, expectedGeneration);
        return updated > 0 || alreadyPublished(componentId, version, expectedGeneration);
    }

    /**
     * 发布事务提交后重入时，默认指针与代次已经推进；把该状态视为幂等成功，避免迟到 worker 将成功发布改成失败。
     */
    private boolean alreadyPublished(UUID componentId, String version, long expectedGeneration) {
        Integer count = jdbc.queryForObject(
                "select count(*) from ai_mcp_component where id = ? and published_version = ? and publication_generation = ?",
                Integer.class,
                componentId,
                version,
                expectedGeneration);
        return count != null && count > 0;
    }

    public UUID insertTestReport(
            UUID mcpId, long draftRevision, String configDigest, String resultJson, boolean success) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into ai_mcp_test_report (id, mcp_id, draft_revision, config_digest, result_json, success) values (?, ?, ?, ?, cast(? as jsonb), ?)",
                id,
                mcpId,
                draftRevision,
                configDigest,
                resultJson,
                success);
        return id;
    }

    public Optional<McpTestReport> findTestReport(UUID reportId) {
        return jdbc
                .query(
                        "select id, mcp_id, draft_revision, config_digest, result_json, success, created_at from ai_mcp_test_report where id = ?",
                        (rows, index) -> new McpTestReport(
                                JdbcRows.uuid(rows, "id"),
                                JdbcRows.uuid(rows, "mcp_id"),
                                rows.getLong("draft_revision"),
                                rows.getString("config_digest"),
                                JdbcRows.json(rows, "result_json"),
                                rows.getBoolean("success"),
                                JdbcRows.instant(rows, "created_at")),
                        reportId)
                .stream()
                .findFirst();
    }

    public Optional<McpTestReport> latestTestReport(UUID mcpId) {
        return jdbc
                .query(
                        "select id, mcp_id, draft_revision, config_digest, result_json, success, created_at from ai_mcp_test_report where mcp_id = ? order by created_at desc limit 1",
                        (rows, index) -> new McpTestReport(
                                JdbcRows.uuid(rows, "id"),
                                JdbcRows.uuid(rows, "mcp_id"),
                                rows.getLong("draft_revision"),
                                rows.getString("config_digest"),
                                JdbcRows.json(rows, "result_json"),
                                rows.getBoolean("success"),
                                JdbcRows.instant(rows, "created_at")),
                        mcpId)
                .stream()
                .findFirst();
    }

    private McpComponent mapComponent(java.sql.ResultSet rows) throws java.sql.SQLException {
        return new McpComponent(
                JdbcRows.uuid(rows, "id"),
                rows.getString("component_key"),
                rows.getString("display_name"),
                rows.getString("description"),
                rows.getString("origin"),
                JdbcRows.json(rows, "native_registry_ref"),
                JdbcRows.json(rows, "draft_json"),
                rows.getString("published_version"),
                rows.getLong("publication_generation"),
                rows.getBoolean("enabled"),
                rows.getLong("revision"),
                rows.getString("created_by"),
                JdbcRows.instant(rows, "created_at"),
                JdbcRows.instant(rows, "updated_at"));
    }

    private McpRelease mapRelease(java.sql.ResultSet rows) throws java.sql.SQLException {
        List<McpToolDescriptor> tools = JsonSupport.readList(rows.getString("tool_catalog"), McpToolDescriptor.class);
        return new McpRelease(
                JdbcRows.uuid(rows, "id"),
                JdbcRows.uuid(rows, "component_id"),
                rows.getString("component_key"),
                rows.getString("version"),
                rows.getString("transport"),
                JdbcRows.json(rows, "endpoint_policy"),
                JdbcRows.uuid(rows, "credential_id"),
                tools,
                rows.getString("catalog_digest"),
                rows.getString("content_digest"),
                rows.getString("state"),
                rows.getLong("generation"),
                JdbcRows.instant(rows, "created_at"));
    }
}
