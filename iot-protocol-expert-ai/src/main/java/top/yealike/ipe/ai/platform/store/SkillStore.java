package top.yealike.ipe.ai.platform.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import top.yealike.ipe.ai.contract.SkillBundle;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.SkillComponent;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.SkillDraftFile;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.SkillRelease;
import top.yealike.ipe.ai.support.JsonSupport;

@Repository
public class SkillStore {

    private static final String COMPONENT_SELECT =
            "select id, component_key, display_name, description, draft_revision, published_version, publication_generation, enabled, revision, created_by, created_at, updated_at from ai_skill_component";

    private final JdbcTemplate jdbc;

    public SkillStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SkillComponent> list() {
        return jdbc.query(COMPONENT_SELECT + " order by component_key", (rows, index) -> mapComponent(rows));
    }

    public Optional<SkillComponent> find(UUID id) {
        return jdbc.query(COMPONENT_SELECT + " where id = ?", (rows, index) -> mapComponent(rows), id).stream()
                .findFirst();
    }

    public Optional<SkillComponent> findByKey(String key) {
        return jdbc
                .query(COMPONENT_SELECT + " where component_key = ?", (rows, index) -> mapComponent(rows), key)
                .stream()
                .findFirst();
    }

    public Optional<SkillComponent> lock(UUID id) {
        return jdbc
                .query(COMPONENT_SELECT + " where id = ? for update", (rows, index) -> mapComponent(rows), id)
                .stream()
                .findFirst();
    }

    public UUID insert(String key, String displayName, String description, String actor) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into ai_skill_component (id, component_key, display_name, description, draft_revision, enabled, revision, created_by) values (?, ?, ?, ?, 0, true, 0, ?)",
                id,
                key,
                displayName,
                description,
                actor);
        return id;
    }

    public int update(UUID id, long expectedRevision, String displayName, String description, boolean enabled) {
        return jdbc.update(
                "update ai_skill_component set display_name = ?, description = ?, enabled = ?, revision = revision + 1, updated_at = now() where id = ? and revision = ?",
                displayName,
                description,
                enabled,
                id,
                expectedRevision);
    }

    public int setEnabled(UUID id, boolean enabled) {
        return jdbc.update(
                "update ai_skill_component set enabled = ?, revision = revision + 1, updated_at = now() where id = ?",
                enabled,
                id);
    }

    public int bumpRevision(UUID id, long expectedRevision) {
        return jdbc.update(
                "update ai_skill_component set revision = revision + 1, updated_at = now() where id = ? and revision = ?",
                id,
                expectedRevision);
    }

    public int delete(UUID id) {
        return jdbc.update("delete from ai_skill_component where id = ?", id);
    }

    public int deleteDraftFiles(UUID id) {
        return jdbc.update("delete from ai_skill_draft_file where skill_id = ?", id);
    }

    public long bindingReferences(UUID skillId) {
        Long count = jdbc.queryForObject(
                "select count(*) from ai_agent_skill_binding b join ai_skill_release r on r.id = b.skill_release_id where r.skill_id = ?",
                Long.class,
                skillId);
        return count == null ? 0 : count;
    }

    public List<SkillDraftFile> files(UUID skillId) {
        return jdbc.query(
                "select id, skill_id, relative_path, content, content_digest, updated_at from ai_skill_draft_file where skill_id = ? order by relative_path",
                (rows, index) -> new SkillDraftFile(
                        JdbcRows.uuid(rows, "id"),
                        JdbcRows.uuid(rows, "skill_id"),
                        rows.getString("relative_path"),
                        rows.getString("content"),
                        rows.getString("content_digest"),
                        JdbcRows.instant(rows, "updated_at")),
                skillId);
    }

    public Optional<SkillDraftFile> file(UUID skillId, String path) {
        return jdbc
                .query(
                        "select id, skill_id, relative_path, content, content_digest, updated_at from ai_skill_draft_file where skill_id = ? and relative_path = ?",
                        (rows, index) -> new SkillDraftFile(
                                JdbcRows.uuid(rows, "id"),
                                JdbcRows.uuid(rows, "skill_id"),
                                rows.getString("relative_path"),
                                rows.getString("content"),
                                rows.getString("content_digest"),
                                JdbcRows.instant(rows, "updated_at")),
                        skillId,
                        path)
                .stream()
                .findFirst();
    }

    public void upsertFile(UUID skillId, String path, String content, String digest) {
        int updated = jdbc.update(
                "update ai_skill_draft_file set content = ?, content_digest = ?, updated_at = now() where skill_id = ? and relative_path = ?",
                content,
                digest,
                skillId,
                path);
        if (updated == 0) {
            jdbc.update(
                    "insert into ai_skill_draft_file (id, skill_id, relative_path, content, content_digest) values (?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    skillId,
                    path,
                    content,
                    digest);
        }
        jdbc.update(
                "update ai_skill_component set draft_revision = draft_revision + 1, updated_at = now() where id = ?",
                skillId);
    }

    public void deleteFile(UUID skillId, String path) {
        jdbc.update("delete from ai_skill_draft_file where skill_id = ? and relative_path = ?", skillId, path);
        jdbc.update(
                "update ai_skill_component set draft_revision = draft_revision + 1, updated_at = now() where id = ?",
                skillId);
    }

    public UUID insertRelease(
            UUID skillId,
            String version,
            long generation,
            String name,
            String description,
            SkillBundle bundle,
            String digest,
            String actor) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into ai_skill_release (id, skill_id, version, generation, name, description, bundle_json, bundle_digest, state, created_by) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, 'STAGED', ?)",
                id,
                skillId,
                version,
                generation,
                name,
                description,
                JsonSupport.write(bundle),
                digest,
                actor);
        return id;
    }

    public Optional<SkillRelease> findRelease(UUID id) {
        return jdbc.query("""
                        select r.id, r.skill_id, c.component_key, r.version, r.name, r.description, r.bundle_json,
                               r.bundle_digest, r.state, r.generation, r.created_at
                          from ai_skill_release r join ai_skill_component c on c.id = r.skill_id
                         where r.id = ?
                        """, (rows, index) -> mapRelease(rows), id).stream().findFirst();
    }

    public Optional<SkillRelease> findRelease(String key, String version) {
        return jdbc.query("""
                        select r.id, r.skill_id, c.component_key, r.version, r.name, r.description, r.bundle_json,
                               r.bundle_digest, r.state, r.generation, r.created_at
                          from ai_skill_release r join ai_skill_component c on c.id = r.skill_id
                         where c.component_key = ? and r.version = ?
                        """, (rows, index) -> mapRelease(rows), key, version).stream()
                .findFirst();
    }

    public List<SkillRelease> releases(UUID skillId) {
        return jdbc.query("""
                select r.id, r.skill_id, c.component_key, r.version, r.name, r.description, r.bundle_json,
                       r.bundle_digest, r.state, r.generation, r.created_at
                  from ai_skill_release r join ai_skill_component c on c.id = r.skill_id
                 where r.skill_id = ? order by r.created_at desc
                """, (rows, index) -> mapRelease(rows), skillId);
    }

    public int setReleaseState(UUID releaseId, String state) {
        return jdbc.update(
                "update ai_skill_release set state = ? where id = ? and state in ('STAGED', 'ACTIVE')",
                state,
                releaseId);
    }

    public boolean publishComponent(UUID skillId, String version, long expectedGeneration) {
        int updated =
                jdbc.update("""
                        update ai_skill_component c
                           set published_version = ?, publication_generation = ?, revision = revision + 1, updated_at = now()
                         where c.id = ?
                           and publication_generation < ? and exists (
                               select 1
                                 from ai_skill_release r
                                where r.skill_id = c.id
                                  and r.version = ?
                                  and r.generation = ?
                                  and r.state = 'ACTIVE'
                           )
                        """, version, expectedGeneration, skillId, expectedGeneration, version, expectedGeneration);
        return updated > 0 || alreadyPublished(skillId, version, expectedGeneration);
    }

    /**
     * 发布事务提交后重入时，默认指针与代次已经推进；把该状态视为幂等成功，避免迟到 worker 将成功发布改成失败。
     */
    private boolean alreadyPublished(UUID skillId, String version, long expectedGeneration) {
        Integer count = jdbc.queryForObject(
                "select count(*) from ai_skill_component where id = ? and published_version = ? and publication_generation = ?",
                Integer.class,
                skillId,
                version,
                expectedGeneration);
        return count != null && count > 0;
    }

    private SkillComponent mapComponent(java.sql.ResultSet rows) throws java.sql.SQLException {
        return new SkillComponent(
                JdbcRows.uuid(rows, "id"),
                rows.getString("component_key"),
                rows.getString("display_name"),
                rows.getString("description"),
                rows.getLong("draft_revision"),
                rows.getString("published_version"),
                rows.getLong("publication_generation"),
                rows.getBoolean("enabled"),
                rows.getLong("revision"),
                rows.getString("created_by"),
                JdbcRows.instant(rows, "created_at"),
                JdbcRows.instant(rows, "updated_at"));
    }

    private SkillRelease mapRelease(java.sql.ResultSet rows) throws java.sql.SQLException {
        return new SkillRelease(
                JdbcRows.uuid(rows, "id"),
                JdbcRows.uuid(rows, "skill_id"),
                rows.getString("component_key"),
                rows.getString("version"),
                rows.getString("name"),
                rows.getString("description"),
                JsonSupport.read(rows.getString("bundle_json"), SkillBundle.class),
                rows.getString("bundle_digest"),
                rows.getString("state"),
                rows.getLong("generation"),
                JdbcRows.instant(rows, "created_at"));
    }
}
